package com.akine.offering.application;

import com.akine.offering.application.PracticasDeOfertaView.PracticaDeOfertaView;
import com.akine.offering.domain.OfertaPractica;
import com.akine.offering.domain.OfertaServicioConsultorio;
import com.akine.offering.domain.exception.PracticaNoElegibleException;
import com.akine.offering.domain.exception.PracticaPrincipalInvalidaException;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaPracticaRepositoryPort;
import com.akine.offering.domain.port.OfferingRepositoryPorts.OfertaRepositoryPort;
import com.akine.organization.spi.AccountContextDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.resource.spi.CatalogoDirectory;
import com.akine.resource.spi.CatalogoSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Que practicas puede prestar una Oferta y cual es su principal (A-9, DP-11, RF-M06-008).
 *
 * <p>Diseno en {@code docs/diseno/AKINE-A-9-oferta-practica.md}. Las reglas que gobiernan esto:
 *
 * <ol>
 *   <li><b>Declara lo que la oferta PUEDE prestar, no lo que se presto.</b> Lo prestado lo dicen
 *       los tratamientos de la sesion. La principal es el defecto para una sesion cerrada sin
 *       tratamientos, y lo aplica quien devenga o consume (F-4, C-4), no este servicio.</li>
 *   <li><b>Lista vacia = la oferta no declara practicas</b>, no "todas". Al reves que las
 *       habilitaciones.</li>
 *   <li><b>Reemplazo de conjunto completo</b>, con el diff del lado del servidor y la
 *       {@code expectedVersion} de la OFERTA, igual que {@link OfertaHabilitacionService}. Las dos
 *       configuraciones comparten la misma version.</li>
 *   <li><b>Escritura en dos fases.</b> Hibernate ejecuta los INSERT antes que los UPDATE al hacer
 *       flush: si la principal pasa a una practica recien agregada, el INSERT con
 *       {@code principal = 1} correria antes que el UPDATE que desmarca la anterior y el reemplazo
 *       legitimo chocaria contra {@code uk_oferta_practica_principal}. Por eso primero se dan de baja
 *       y se desmarcan las filas que salen, con flush, y recien despues se insertan y se marcan las
 *       que entran.</li>
 * </ol>
 */
@Service
public class OfertaPracticaService {

	private static final Logger log = LoggerFactory.getLogger(OfertaPracticaService.class);

	/** Motivo con el que se cierran las practicas que salen de un reemplazo. */
	static final String MOTIVO_REEMPLAZO = "Quitada al reconfigurar las practicas de la oferta";

	private final OfertaPracticaRepositoryPort practicas;
	private final CatalogoDirectory catalogo;
	private final AuditTrail auditTrail;
	private final AccesoALaConfiguracionDeOferta acceso;

	public OfertaPracticaService(
			OfertaRepositoryPort ofertas,
			OfertaPracticaRepositoryPort practicas,
			ConsultorioDirectory consultorioDirectory,
			AccountContextDirectory accountContextDirectory,
			CatalogoDirectory catalogo,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.practicas = practicas;
		this.catalogo = catalogo;
		this.auditTrail = auditTrail;
		this.acceso = new AccesoALaConfiguracionDeOferta(
				ofertas, consultorioDirectory, accountContextDirectory, permissionGuard);
	}

	/** Las practicas de la oferta, activas e inactivas, con la principal vigente. */
	@Transactional(readOnly = true)
	public PracticasDeOfertaView leer(
			OperatingActor actor, long organizationId, long consultorioId, long ofertaId) {

		acceso.exigirLectura(actor, organizationId, consultorioId);
		OfertaServicioConsultorio oferta = acceso.cargar(organizationId, consultorioId, ofertaId);
		return armarVista(oferta.getVersion(), organizationId, ofertaId);
	}

	/**
	 * Reemplaza el conjunto completo de practicas y fija la principal.
	 *
	 * @param practicaIds         las que quedan; vacia deja la oferta sin practicas declaradas
	 * @param practicaPrincipalId obligatoria y dentro de la lista si la lista no es vacia;
	 *                            {@code null} si la lista es vacia
	 */
	@Transactional
	public PracticasDeOfertaView reemplazar(
			OperatingActor actor,
			long organizationId,
			long consultorioId,
			long ofertaId,
			Collection<Long> practicaIds,
			Long practicaPrincipalId,
			long expectedVersion) {

		OfertaServicioConsultorio oferta = acceso.exigirOfertaConfigurable(
				actor, organizationId, consultorioId, ofertaId, expectedVersion);

		Set<Long> pedidas = normalizar(practicaIds);
		exigirPrincipalCoherente(pedidas, practicaPrincipalId);

		List<OfertaPractica> actuales = practicas
				.findAllByOrganizationIdAndOfertaIdAndActiveOrderByIdAsc(organizationId, ofertaId, true);
		Map<Long, OfertaPractica> vigentes = new LinkedHashMap<>();
		for (OfertaPractica fila : actuales) {
			vigentes.put(fila.getPracticaId(), fila);
		}
		Long principalAnterior = actuales.stream()
				.filter(OfertaPractica::isPrincipal)
				.map(OfertaPractica::getPracticaId)
				.findFirst()
				.orElse(null);

		Instant ahora = Instant.now();
		// Se valida TODO lo que entra antes de escribir nada: un 404 a mitad del diff no deja
		// filas a medias (la transaccion se revierte igual), pero si deja auditoria y log mentirosos.
		for (Long practicaId : pedidas) {
			if (!vigentes.containsKey(practicaId)) {
				exigirElegible(organizationId, practicaId, ahora);
			}
		}

		// Fase 1: bajas y desmarcado, volcados a la base antes de cualquier INSERT.
		boolean huboFaseUno = false;
		for (OfertaPractica fila : actuales) {
			if (!pedidas.contains(fila.getPracticaId())) {
				fila.deactivate(ahora, MOTIVO_REEMPLAZO);
				practicas.save(fila);
				auditar(AuditEvents.OFERTA_PRACTICA_REMOVED, oferta, actor,
						Map.of("practicaId", String.valueOf(fila.getPracticaId())));
				huboFaseUno = true;
			} else if (fila.isPrincipal() && !fila.getPracticaId().equals(practicaPrincipalId)) {
				fila.desmarcarPrincipal();
				practicas.save(fila);
				huboFaseUno = true;
			}
		}
		if (huboFaseUno) {
			practicas.flush();
		}

		// Fase 2: altas y la marca nueva.
		for (Long practicaId : pedidas) {
			if (!vigentes.containsKey(practicaId)) {
				practicas.save(new OfertaPractica(organizationId, consultorioId, ofertaId, practicaId,
						practicaId.equals(practicaPrincipalId)));
				auditar(AuditEvents.OFERTA_PRACTICA_ADDED, oferta, actor,
						Map.of("practicaId", String.valueOf(practicaId)));
			}
		}
		OfertaPractica nuevaPrincipal =
				practicaPrincipalId == null ? null : vigentes.get(practicaPrincipalId);
		if (nuevaPrincipal != null && !nuevaPrincipal.isPrincipal()) {
			nuevaPrincipal.marcarPrincipal();
			practicas.save(nuevaPrincipal);
		}

		if (!Objects.equals(principalAnterior, practicaPrincipalId)) {
			Map<String, String> detalle = new LinkedHashMap<>();
			detalle.put("anterior", String.valueOf(principalAnterior));
			detalle.put("nueva", String.valueOf(practicaPrincipalId));
			auditar(AuditEvents.OFERTA_PRACTICA_PRINCIPAL_CHANGED, oferta, actor, detalle);
		}

		log.info("Practicas de oferta reconfiguradas: ofertaId={} practicas={}",
				ofertaId, pedidas.size());

		// leida + 1: el reemplazo no ensucia la oferta y el unico avance es el forzado. Mismo
		// razonamiento que OfertaHabilitacionService#vistaTrasReemplazo.
		return armarVista(oferta.getVersion() + 1, organizationId, ofertaId);
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private static void exigirPrincipalCoherente(Set<Long> pedidas, Long principalId) {
		if (pedidas.isEmpty()) {
			if (principalId != null) {
				throw new PracticaPrincipalInvalidaException(
						"Una oferta sin practicas no puede tener practica principal: mandala en null");
			}
			return;
		}
		if (principalId == null) {
			throw new PracticaPrincipalInvalidaException(
					"Con al menos una practica, la principal es obligatoria");
		}
		if (!pedidas.contains(principalId)) {
			throw new PracticaPrincipalInvalidaException(
					"La practica principal tiene que estar entre las practicas de la oferta");
		}
	}

	/** La practica existe, el tenant la ve y se puede elegir hoy. Ver la excepcion. */
	private void exigirElegible(long organizationId, long practicaId, Instant ahora) {
		CatalogoSnapshot practica = catalogo.findPractica(organizationId, practicaId, ahora)
				.orElseThrow(() -> new PracticaNoElegibleException(
						practicaId, PracticaNoElegibleException.Motivo.INEXISTENTE));
		if (!practica.vigente()) {
			throw new PracticaNoElegibleException(
					practicaId, PracticaNoElegibleException.Motivo.NO_VIGENTE);
		}
	}

	private PracticasDeOfertaView armarVista(long ofertaVersion, long organizationId, long ofertaId) {
		Instant ahora = Instant.now();
		List<OfertaPractica> filas =
				practicas.findAllByOrganizationIdAndOfertaIdOrderByIdAsc(organizationId, ofertaId);

		Long principal = null;
		List<PracticaDeOfertaView> vistas = new ArrayList<>();
		for (OfertaPractica fila : filas) {
			if (fila.isOperable() && fila.isPrincipal()) {
				principal = fila.getPracticaId();
			}
			Optional<CatalogoSnapshot> enCatalogo =
					catalogo.findPractica(organizationId, fila.getPracticaId(), ahora);
			vistas.add(new PracticaDeOfertaView(
					fila.getId(),
					fila.getPracticaId(),
					enCatalogo.map(CatalogoSnapshot::codigo).orElse(null),
					enCatalogo.map(CatalogoSnapshot::name).orElse(null),
					fila.isPrincipal(),
					fila.isOperable() ? "ACTIVO" : "INACTIVO",
					enCatalogo.map(CatalogoSnapshot::vigente).orElse(false),
					fila.getDeletedAt(),
					fila.getDeactivationReason(),
					fila.getVersion()));
		}
		return new PracticasDeOfertaView(ofertaId, ofertaVersion, principal, List.copyOf(vistas));
	}

	private static Set<Long> normalizar(Collection<Long> ids) {
		Set<Long> limpio = new LinkedHashSet<>();
		if (ids != null) {
			for (Long id : ids) {
				if (id != null) {
					limpio.add(id);
				}
			}
		}
		return limpio;
	}

	private void auditar(
			String eventType,
			OfertaServicioConsultorio oferta,
			OperatingActor actor,
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
				null,
				AuditEvents.correlationId(),
				Instant.now()));
	}
}
