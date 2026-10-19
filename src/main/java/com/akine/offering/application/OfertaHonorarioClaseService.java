package com.akine.offering.application;

import com.akine.offering.domain.OfertaHonorarioClase;
import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.domain.exception.HonorarioDeClaseInactivoException;
import com.akine.offering.domain.exception.HonorarioDeClaseNoAccesibleException;
import com.akine.offering.domain.exception.HonorarioDeClaseSolapadoException;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaHonorarioClaseRepositoryPort;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaRepositoryPort;
import com.akine.offering.spi.HonorariosDeClase.HonorarioDeClase;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Honorario por clase de una oferta por vigencia (AKINE-08.09b, DP-38, RF-M22-006).
 *
 * <p>Mismo diseno que {@link OfertaPrecioParticularService}: el no-solapamiento se hace cumplir
 * en {@code READ_COMMITTED} con el lock exclusivo de la fila de la oferta tomado ANTES de leer el
 * conjunto. La diferencia es el permiso de lectura: aca tambien {@code consultorio:manage},
 * porque es lo que cobra un colega.
 */
@Service
public class OfertaHonorarioClaseService {

	private static final Logger log = LoggerFactory.getLogger(OfertaHonorarioClaseService.class);

	private final OfertaHonorarioClaseRepositoryPort honorarios;
	private final AuditTrail auditTrail;
	private final AccesoALaConfiguracionDeOferta acceso;
	private final ConsultorioDirectory consultorios;
	private final Clock clock;

	public OfertaHonorarioClaseService(
			OfertaRepositoryPort ofertas,
			OfertaHonorarioClaseRepositoryPort honorarios,
			ConsultorioDirectory consultorioDirectory,
			AccountContextDirectory accountContextDirectory,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.honorarios = honorarios;
		this.auditTrail = auditTrail;
		this.consultorios = consultorioDirectory;
		this.clock = Clock.systemUTC();
		this.acceso = new AccesoALaConfiguracionDeOferta(
				ofertas, consultorioDirectory, accountContextDirectory, permissionGuard);
	}

	/** La grilla de vigencias: activos e historicos, del mas nuevo al mas viejo. */
	@Transactional(readOnly = true)
	public List<OfertaHonorarioClaseView> listar(
			OperatingActor actor, long organizationId, long consultorioId, long ofertaId) {

		acceso.exigirLecturaDeGestion(actor, organizationId, consultorioId);
		acceso.cargar(organizationId, consultorioId, ofertaId);
		LocalDate hoy = hoy(organizationId, consultorioId);
		return honorarios
				.findAllByOrganizationIdAndOfertaIdOrderByVigenciaDesdeDescIdDesc(organizationId, ofertaId)
				.stream()
				.map(h -> OfertaHonorarioClaseView.de(h, hoy))
				.toList();
	}

	/** Costura para {@code HonorariosDeClase}: no autoriza nada. */
	@Transactional(readOnly = true)
	public List<HonorarioDeClase> activosDe(long organizationId, Collection<Long> ofertaIds) {
		if (ofertaIds.isEmpty()) {
			return List.of();
		}
		return honorarios.findAllByOrganizationIdAndOfertaIdInAndActive(organizationId, ofertaIds, true)
				.stream()
				.map(h -> new HonorarioDeClase(h.getId(), h.getOfertaId(), h.getImportePorParticipante(),
						h.getMoneda(), h.getVigenciaDesde(), h.getVigenciaHasta()))
				.toList();
	}

	/** Alta de una tarifa con vigencia. Sin moneda, hereda la del precio de lista de la oferta. */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	@SuppressWarnings("java:S107")
	public OfertaHonorarioClaseView crear(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			BigDecimal importePorParticipante,
			String moneda,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		// EL ORDEN ES LA GARANTIA: lock de la oferta primero, despues leer el conjunto.
		OfertaServicioConsultorio oferta =
				acceso.exigirOfertaParaPrecio(actor, organizationId, consultorioId, ofertaId);

		OfertaHonorarioClase honorario = new OfertaHonorarioClase(
				organizationId, consultorioId, ofertaId, importePorParticipante,
				moneda == null ? oferta.getMoneda() : moneda, vigenciaDesde, vigenciaHasta);
		exigirSinSolapamiento(organizationId, ofertaId, vigenciaDesde, vigenciaHasta, null);

		OfertaHonorarioClase creado = honorarios.saveAndFlush(honorario);
		auditar(AuditEvents.OFERTA_HONORARIO_CLASE_CREATED, oferta, actor, null, detalle(creado));
		log.info("Honorario por clase creado: honorarioId={} ofertaId={} periodo={}",
				creado.getId(), ofertaId, creado.periodo());
		return OfertaHonorarioClaseView.de(creado, hoy(organizationId, consultorioId));
	}

	/** Cambia el fin de la vigencia. Toma el lock: extenderla puede crear un solapamiento. */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	@SuppressWarnings("java:S107")
	public OfertaHonorarioClaseView cambiarFin(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			long honorarioId,
			LocalDate vigenciaHasta,
			long expectedVersion) {

		OfertaServicioConsultorio oferta =
				acceso.exigirOfertaParaPrecio(actor, organizationId, consultorioId, ofertaId);
		OfertaHonorarioClase honorario = cargarOperable(organizationId, ofertaId, honorarioId);
		if (honorario.getVersion() != expectedVersion) {
			throw new OptimisticLockingFailureException(
					"El honorario por clase fue modificado por otra operacion");
		}

		String anterior = honorario.periodo();
		honorario.cambiarFin(vigenciaHasta);
		exigirSinSolapamiento(
				organizationId, ofertaId, honorario.getVigenciaDesde(), vigenciaHasta, honorarioId);
		OfertaHonorarioClase guardado = honorarios.saveAndFlush(honorario);

		Map<String, String> detalle = detalle(guardado);
		detalle.put("periodoAnterior", anterior);
		auditar(AuditEvents.OFERTA_HONORARIO_CLASE_UPDATED, oferta, actor, null, detalle);
		return OfertaHonorarioClaseView.de(guardado, hoy(organizationId, consultorioId));
	}

	/** Baja logica con motivo. Libera el periodo; lo ya liquidado guardo su importe. */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public OfertaHonorarioClaseView darDeBaja(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			long honorarioId,
			String motivo) {

		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("La baja de un honorario por clase exige un motivo");
		}
		OfertaServicioConsultorio oferta =
				acceso.exigirOfertaParaPrecio(actor, organizationId, consultorioId, ofertaId);
		OfertaHonorarioClase honorario = cargarOperable(organizationId, ofertaId, honorarioId);

		honorario.deactivate(Instant.now(), motivo.strip());
		OfertaHonorarioClase guardado = honorarios.saveAndFlush(honorario);

		auditar(AuditEvents.OFERTA_HONORARIO_CLASE_DEACTIVATED, oferta, actor, motivo.strip(),
				detalle(guardado));
		return OfertaHonorarioClaseView.de(guardado, hoy(organizationId, consultorioId));
	}

	private LocalDate hoy(long organizationId, long consultorioId) {
		return ZonaDeSede.hoy(consultorios, organizationId, consultorioId, clock);
	}

	/** <b>Se llama SIEMPRE con el lock de la oferta tomado.</b> */
	private void exigirSinSolapamiento(
			long organizationId, long ofertaId, LocalDate desde, LocalDate hasta, Long excluirId) {

		honorarios.findAllByOrganizationIdAndOfertaIdAndActiveOrderByVigenciaDesdeDescIdDesc(
						organizationId, ofertaId, true)
				.stream()
				.filter(h -> !h.getId().equals(excluirId))
				.filter(h -> h.seSolapaCon(desde, hasta))
				.findFirst()
				.ifPresent(choque -> {
					throw new HonorarioDeClaseSolapadoException(choque.getId(), choque.periodo());
				});
	}

	private OfertaHonorarioClase cargarOperable(long organizationId, long ofertaId, long honorarioId) {
		OfertaHonorarioClase honorario = honorarios
				.findByIdAndOrganizationIdAndOfertaId(honorarioId, organizationId, ofertaId)
				.orElseThrow(() -> new HonorarioDeClaseNoAccesibleException(honorarioId));
		if (!honorario.isActive()) {
			throw new HonorarioDeClaseInactivoException(honorarioId);
		}
		return honorario;
	}

	private static Map<String, String> detalle(OfertaHonorarioClase honorario) {
		Map<String, String> detalle = new LinkedHashMap<>();
		detalle.put("honorarioId", String.valueOf(honorario.getId()));
		detalle.put("importePorParticipante",
				honorario.getImportePorParticipante() + " " + honorario.getMoneda());
		detalle.put("periodo", honorario.periodo());
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
