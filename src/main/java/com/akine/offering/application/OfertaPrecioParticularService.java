package com.akine.offering.application;

import com.akine.offering.domain.OfertaPrecioParticular;
import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.domain.exception.PrecioParticularInactivoException;
import com.akine.offering.domain.exception.PrecioParticularNoAccesibleException;
import com.akine.offering.domain.exception.PrecioParticularSolapadoException;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaPrecioParticularRepositoryPort;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaRepositoryPort;
import com.akine.offering.spi.PrecioDeOferta;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Precio particular de una oferta por vigencia (B-3, RF-M16-009, RN-M16-007).
 *
 * <h2>Resolver el precio de un dia</h2>
 *
 * <pre>
 *   el precio particular ACTIVO que cubre ese dia     (como mucho uno: no se solapan)
 *   si no hay: oferta.precio_base                      (el precio de lista, como hasta V79)
 * </pre>
 *
 * <p>Una oferta sin precios particulares se comporta exactamente como antes de B-3. El precio que
 * se resuelve es una lectura VIVA: el devengo lo copia a la obligacion al cerrar la sesion (07.01)
 * y nunca lo vuelve a leer, que es lo que hace cumplir CA-M16-009-06.
 *
 * <h2>El no-solapamiento, con las tres condiciones de siempre</h2>
 *
 * <p>{@code READ_COMMITTED}, el lock exclusivo de la fila de la oferta tomado ANTES de leer el
 * conjunto, y la fila-lock que ya existe (la oferta) — no hace falta crear una. Mismo patron que
 * {@code convenio_lock} en {@code contracting}, con un punto de serializacion por oferta en vez
 * de por sede.
 */
@Service
public class OfertaPrecioParticularService {

	private static final Logger log = LoggerFactory.getLogger(OfertaPrecioParticularService.class);

	private final OfertaRepositoryPort ofertas;
	private final OfertaPrecioParticularRepositoryPort precios;
	private final AuditTrail auditTrail;
	private final AccesoALaConfiguracionDeOferta acceso;

	public OfertaPrecioParticularService(
			OfertaRepositoryPort ofertas,
			OfertaPrecioParticularRepositoryPort precios,
			ConsultorioDirectory consultorioDirectory,
			AccountContextDirectory accountContextDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.ofertas = ofertas;
		this.precios = precios;
		this.auditTrail = auditTrail;
		this.acceso = new AccesoALaConfiguracionDeOferta(
				ofertas, consultorioDirectory, accountContextDirectory, permissionGuard);
	}

	// =================================================================================
	// Lecturas
	// =================================================================================

	/** La grilla de vigencias: activos e historicos, del mas nuevo al mas viejo. */
	@Transactional(readOnly = true)
	public List<OfertaPrecioParticularView> listar(
			OperatingActor actor, long organizationId, long consultorioId, long ofertaId) {

		acceso.exigirLectura(actor, organizationId, consultorioId);
		acceso.cargar(organizationId, consultorioId, ofertaId);
		LocalDate hoy = LocalDate.now();
		return precios
				.findAllByOrganizationIdAndOfertaIdOrderByVigenciaDesdeDescIdDesc(
						organizationId, ofertaId)
				.stream()
				.map(p -> OfertaPrecioParticularView.de(p, hoy))
				.toList();
	}

	/**
	 * Lo que cuesta la oferta ese dia: el precio particular vigente o el precio de lista. Costura
	 * para {@code OfertaDirectory}: no autoriza nada. {@code empty} si la oferta no es de esa sede.
	 */
	@Transactional(readOnly = true)
	public Optional<PrecioDeOferta> precioVigenteEl(
			long organizationId, long consultorioId, long ofertaId, LocalDate fecha) {

		return ofertas
				.findByIdAndOrganizationIdAndConsultorioId(ofertaId, organizationId, consultorioId)
				.map(oferta -> precios
						.findAllByOrganizationIdAndOfertaIdAndActiveOrderByVigenciaDesdeDescIdDesc(
								organizationId, ofertaId, true)
						.stream()
						.filter(p -> p.aplicaEl(fecha))
						.findFirst()
						.map(p -> new PrecioDeOferta(
								oferta.getId(), p.getImporte(), p.getMoneda(),
								oferta.isAdmiteObraSocial()))
						.orElseGet(() -> new PrecioDeOferta(
								oferta.getId(), oferta.getPrecioBase(), oferta.getMoneda(),
								oferta.isAdmiteObraSocial())));
	}

	// =================================================================================
	// Mutaciones
	// =================================================================================

	/**
	 * Alta de un precio particular con vigencia. Sin moneda, hereda la del precio de lista de la
	 * oferta; si la oferta tampoco tiene, es 400.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	@SuppressWarnings("java:S107")
	public OfertaPrecioParticularView crear(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			BigDecimal importe,
			String moneda,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		// EL ORDEN ES LA GARANTIA: lock de la oferta primero, despues leer el conjunto.
		OfertaServicioConsultorio oferta =
				acceso.exigirOfertaParaPrecio(actor, organizationId, consultorioId, ofertaId);

		OfertaPrecioParticular precio = new OfertaPrecioParticular(
				organizationId, consultorioId, ofertaId, importe,
				moneda == null ? oferta.getMoneda() : moneda, vigenciaDesde, vigenciaHasta);
		exigirSinSolapamiento(organizationId, ofertaId, vigenciaDesde, vigenciaHasta, null);

		OfertaPrecioParticular creado = precios.saveAndFlush(precio);

		auditar(AuditEvents.OFERTA_PRECIO_PARTICULAR_CREATED, oferta, actor, null, detalle(creado));
		log.info("Precio particular creado: precioId={} ofertaId={} periodo={}",
				creado.getId(), ofertaId, creado.periodo());
		return OfertaPrecioParticularView.de(creado, LocalDate.now());
	}

	/**
	 * Cambia el fin de la vigencia: cerrar el precio actual el dia antes de que rija el nuevo, o
	 * reabrirlo con {@code null}. Es lo unico editable: el importe no se toca (ver la entidad).
	 * Tambien toma el lock: extender una vigencia puede crear el solapamiento que el alta impide.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	@SuppressWarnings("java:S107")
	public OfertaPrecioParticularView cambiarFin(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			long precioId,
			LocalDate vigenciaHasta,
			long expectedVersion) {

		OfertaServicioConsultorio oferta =
				acceso.exigirOfertaParaPrecio(actor, organizationId, consultorioId, ofertaId);
		OfertaPrecioParticular precio = cargarOperable(organizationId, ofertaId, precioId);
		if (precio.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"El precio particular fue modificado por otra operacion");
		}

		String anterior = precio.periodo();
		precio.cambiarFin(vigenciaHasta);
		exigirSinSolapamiento(
				organizationId, ofertaId, precio.getVigenciaDesde(), vigenciaHasta, precioId);
		OfertaPrecioParticular guardado = precios.saveAndFlush(precio);

		Map<String, String> detalle = detalle(guardado);
		detalle.put("periodoAnterior", anterior);
		auditar(AuditEvents.OFERTA_PRECIO_PARTICULAR_UPDATED, oferta, actor, null, detalle);
		return OfertaPrecioParticularView.de(guardado, LocalDate.now());
	}

	/** Baja logica con motivo. Libera el periodo; lo ya devengado guardo su importe. */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public OfertaPrecioParticularView darDeBaja(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			long precioId,
			String motivo) {

		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("La baja de un precio particular exige un motivo");
		}
		OfertaServicioConsultorio oferta =
				acceso.exigirOfertaParaPrecio(actor, organizationId, consultorioId, ofertaId);
		OfertaPrecioParticular precio = cargarOperable(organizationId, ofertaId, precioId);

		precio.deactivate(Instant.now(), motivo.strip());
		OfertaPrecioParticular guardado = precios.saveAndFlush(precio);

		auditar(AuditEvents.OFERTA_PRECIO_PARTICULAR_DEACTIVATED, oferta, actor, motivo.strip(),
				detalle(guardado));
		log.info("Precio particular dado de baja: precioId={} ofertaId={}", precioId, ofertaId);
		return OfertaPrecioParticularView.de(guardado, LocalDate.now());
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	/** <b>Se llama SIEMPRE con el lock de la oferta tomado.</b> */
	private void exigirSinSolapamiento(
			long organizationId, long ofertaId, LocalDate desde, LocalDate hasta, Long excluirId) {

		precios.findAllByOrganizationIdAndOfertaIdAndActiveOrderByVigenciaDesdeDescIdDesc(
						organizationId, ofertaId, true)
				.stream()
				.filter(p -> !p.getId().equals(excluirId))
				.filter(p -> p.seSolapaCon(desde, hasta))
				.findFirst()
				.ifPresent(choque -> {
					throw new PrecioParticularSolapadoException(choque.getId(), choque.periodo());
				});
	}

	private OfertaPrecioParticular cargarOperable(long organizationId, long ofertaId, long precioId) {
		OfertaPrecioParticular precio = precios
				.findByIdAndOrganizationIdAndOfertaId(precioId, organizationId, ofertaId)
				.orElseThrow(() -> new PrecioParticularNoAccesibleException(precioId));
		if (!precio.isActive()) {
			throw new PrecioParticularInactivoException(precioId);
		}
		return precio;
	}

	private static Map<String, String> detalle(OfertaPrecioParticular precio) {
		Map<String, String> detalle = new LinkedHashMap<>();
		detalle.put("precioId", String.valueOf(precio.getId()));
		detalle.put("importe", precio.getImporte() + " " + precio.getMoneda());
		detalle.put("periodo", precio.periodo());
		return detalle;
	}

	private void auditar(
			String eventType,
			OfertaServicioConsultorio oferta,
			OperatingActor actor,
			String reason,
			Map<String, String> details) {

		auditTrail.record(new AuditEntry(
				oferta.getOrganizationId(),
				oferta.getConsultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_HABILITACION,
				oferta.getId(),
				null,
				null,
				details,
				reason,
				AuditEvents.correlationId(),
				Instant.now()));
	}
}
