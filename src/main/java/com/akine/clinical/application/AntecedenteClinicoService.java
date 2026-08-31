package com.akine.clinical.application;

import com.akine.clinical.domain.AntecedenteClinico;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.PermissionCodes;
import com.akine.clinical.domain.TipoAntecedente;
import com.akine.clinical.domain.exception.AntecedenteNotAccessibleException;
import com.akine.clinical.domain.exception.HistoriaClinicaNotAccessibleException;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AntecedenteClinicoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.organization.spi.PermissionGuard;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Antecedentes medicos, quirurgicos, alergias y medicacion (RF-M09-002).
 *
 * <h2>Registrar y dar de baja. No editar.</h2>
 *
 * <p>No hay operacion de edicion, y la ausencia es el diseño. RN-M09-004 exige que los cambios
 * clinicos sensibles sean trazables: un {@code UPDATE} sobre la descripcion de un antecedente pisa
 * lo anterior sin dejar rastro de que decia. Corregir un antecedente es <b>darlo de baja con
 * motivo y registrar el nuevo</b>, y asi quedan las dos versiones con sus autores y sus fechas.
 *
 * <p>La baja tampoco borra: es logica, con motivo obligatorio, y el antecedente sigue siendo
 * consultable (regla maestra 10).
 *
 * <h2>Por que este servicio tambien evalua el acceso clinico completo</h2>
 *
 * <p>Registrar un antecedente exige {@code hc:write} <b>y</b> relacion asistencial o justificacion,
 * igual que leer la historia. Podria parecer excesivo —quien escribe ya demostro que participa—
 * pero DP-03 no distingue: escribir en la historia de un paciente al que no se atiende es tan
 * sensible como leerla, y ademas deja rastro permanente.
 */
@Service
public class AntecedenteClinicoService {

	private static final Logger log = LoggerFactory.getLogger(AntecedenteClinicoService.class);

	private final HistoriaClinicaRepositoryPort historias;
	private final AntecedenteClinicoRepositoryPort antecedentes;
	private final PermissionGuard permissionGuard;
	private final RelacionAsistencialProbe relaciones;
	private final AuditTrail auditTrail;
	private final ClinicalSupportAccessAuditor supportAccessAuditor;

	public AntecedenteClinicoService(
			HistoriaClinicaRepositoryPort historias,
			AntecedenteClinicoRepositoryPort antecedentes,
			PermissionGuard permissionGuard,
			RelacionAsistencialProbe relaciones,
			AuditTrail auditTrail,
			ClinicalSupportAccessAuditor supportAccessAuditor) {

		this.historias = historias;
		this.antecedentes = antecedentes;
		this.permissionGuard = permissionGuard;
		this.relaciones = relaciones;
		this.auditTrail = auditTrail;
		this.supportAccessAuditor = supportAccessAuditor;
	}

	/** Registra un antecedente en la historia vigente de esa persona. */
	@Transactional
	public AntecedenteView registrar(
			OperatingActor actor,
			long personaId,
			TipoAntecedente tipo,
			String descripcion,
			String justificacion) {

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_WRITE, personaId, justificacion, "Registrar antecedente clinico");
		long organizationId = actor.contextOrganizationId();

		HistoriaClinica historia = historias.buscarVigentePorPersona(organizationId, personaId)
				.orElseThrow(() -> new HistoriaClinicaNotAccessibleException(personaId));

		Instant ahora = Instant.now();
		AntecedenteClinico guardado = antecedentes.save(new AntecedenteClinico(
				organizationId, historia.getId(), tipo, descripcion, ahora, actor.accountId()));

		// La descripcion NO va en los detalles de la auditoria: es contenido clinico, y
		// `audit_event` se consulta con auditoria:read, que no es un permiso clinico. Copiarla ahi
		// convertiria la auditoria en una via de lectura clinica sin permiso clinico.
		auditar(AuditEvents.ANTECEDENTE_REGISTERED, guardado.getId(), actor, acceso, null, "VIGENTE",
				Map.of("historiaClinicaId", String.valueOf(historia.getId()),
						"tipo", tipo.name()),
				ahora);
		registrarSoporte(acceso, actor, guardado, "Registrar antecedente clinico", ahora);

		log.info("Antecedente clinico registrado: id={} historiaClinicaId={} tipo={}",
				guardado.getId(), historia.getId(), tipo);
		return AntecedenteView.de(guardado);
	}

	/**
	 * Da de baja un antecedente que dejo de aplicar, con motivo obligatorio.
	 *
	 * <p>El motivo lo exige el dominio y tambien la base
	 * ({@code ck_hc_antecedente_baja_coherente}): un antecedente clinico que desaparece sin
	 * explicacion es exactamente lo que RN-M09-004 quiere impedir.
	 */
	@Transactional
	public AntecedenteView darDeBaja(
			OperatingActor actor,
			long personaId,
			long antecedenteId,
			String motivo,
			String justificacion) {

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_WRITE, personaId, justificacion, "Dar de baja un antecedente");
		long organizationId = actor.contextOrganizationId();

		HistoriaClinica historia = historias.buscarVigentePorPersona(organizationId, personaId)
				.orElseThrow(() -> new HistoriaClinicaNotAccessibleException(personaId));

		AntecedenteClinico antecedente = antecedentes
				.findByIdAndOrganizationId(antecedenteId, organizationId)
				.filter(a -> a.getHistoriaClinicaId().equals(historia.getId()))
				.orElseThrow(() -> new AntecedenteNotAccessibleException(antecedenteId));

		if (!antecedente.isVigente()) {
			// Ya estaba de baja: el mismo pedido repetido, no un conflicto. Se devuelve como
			// estaba, con su motivo original intacto — pisarlo con el nuevo perderia el primero.
			log.debug("Antecedente ya dado de baja: id={}", antecedenteId);
			return AntecedenteView.de(antecedente);
		}

		Instant ahora = Instant.now();
		antecedente.deactivate(ahora, motivo);
		AntecedenteClinico guardado = antecedentes.save(antecedente);

		auditar(AuditEvents.ANTECEDENTE_DEACTIVATED, guardado.getId(), actor, acceso,
				"VIGENTE", "DADO_DE_BAJA",
				Map.of("historiaClinicaId", String.valueOf(historia.getId()),
						"tipo", guardado.getTipo().name()),
				ahora);
		registrarSoporte(acceso, actor, guardado, "Dar de baja un antecedente", ahora);

		return AntecedenteView.de(guardado);
	}

	/**
	 * Los antecedentes de la historia de esa persona.
	 *
	 * <p>Es una lectura clinica y se audita como tal: quien consulta las alergias de un paciente
	 * esta accediendo a la historia, aunque no pida la historia entera.
	 */
	@Transactional
	public List<AntecedenteView> listar(
			OperatingActor actor,
			long personaId,
			TipoAntecedente tipo,
			boolean soloVigentes,
			String justificacion) {

		AccesoClinico acceso = AutorizacionClinica.exigir(permissionGuard, relaciones, actor,
				PermissionCodes.HC_READ, personaId, justificacion, "Listar antecedentes clinicos");
		long organizationId = actor.contextOrganizationId();

		HistoriaClinica historia = historias.buscarVigentePorPersona(organizationId, personaId)
				.orElseThrow(() -> new HistoriaClinicaNotAccessibleException(personaId));

		auditar(AuditEvents.HISTORIA_CLINICA_ACCESSED, historia.getId(), actor, acceso, null, null,
				Map.of("personaId", String.valueOf(personaId), "alcance", "ANTECEDENTES"),
				Instant.now());

		return antecedentes.buscarDeHistoria(organizationId, historia.getId(), tipo, soloVigentes)
				.stream()
				.map(AntecedenteView::de)
				.toList();
	}

	private void auditar(
			String eventType,
			Long entityId,
			OperatingActor actor,
			AccesoClinico acceso,
			String previo,
			String nuevo,
			Map<String, String> detalles,
			Instant ahora) {

		var conVia = new LinkedHashMap<>(detalles);
		conVia.put("viaDeAcceso", acceso.via());

		auditTrail.record(new AuditEntry(
				actor.contextOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.HISTORIA_CLINICA_ACCESSED.equals(eventType)
						? AuditEvents.ENTITY_HISTORIA_CLINICA
						: AuditEvents.ENTITY_ANTECEDENTE,
				entityId,
				previo,
				nuevo,
				conVia,
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora));
	}

	private void registrarSoporte(
			AccesoClinico acceso,
			OperatingActor actor,
			AntecedenteClinico antecedente,
			String operacion,
			Instant ahora) {

		if (!acceso.viaSupportAccess()) {
			return;
		}
		supportAccessAuditor.record(new AuditEntry(
				antecedente.getOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				"SUPPORT_ACCESS_USED",
				AuditEvents.ENTITY_ANTECEDENTE,
				antecedente.getId(),
				null,
				null,
				Map.of("operacion", operacion),
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora));
	}
}
