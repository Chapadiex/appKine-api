package com.akine.clinical.application;

import com.akine.clinical.domain.CasoClinico;
import com.akine.clinical.domain.DerivacionClinica;
import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.PermissionCodes;
import com.akine.clinical.domain.PlanTratamiento;
import com.akine.clinical.domain.exception.AutorizacionNoElegibleException;
import com.akine.clinical.domain.exception.CasoClinicoNotAccessibleException;
import com.akine.clinical.domain.exception.CasoNoActivoException;
import com.akine.clinical.domain.exception.DerivacionNotAccessibleException;
import com.akine.clinical.domain.exception.PlanTratamientoNotAccessibleException;
import com.akine.clinical.domain.port.CasoRepositoryPorts.CasoClinicoRepositoryPort;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import com.akine.clinical.domain.port.DerivacionRepositoryPorts.DerivacionClinicaRepositoryPort;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanTratamientoRepositoryPort;
import com.akine.clinical.spi.ActorDeDerivacion;
import com.akine.clinical.spi.DerivacionSnapshot;
import com.akine.clinical.spi.EstadoClinicoDeParticipacion;
import com.akine.clinical.spi.OrigenDeParticipacion;
import com.akine.clinical.spi.ParticipacionDerivable;
import com.akine.clinical.spi.RegistroDeDerivacion;
import com.akine.clinical.spi.RelacionAsistencialProbe;
import com.akine.clinical.spi.ReversionDeDerivacion;
import com.akine.organization.spi.PermissionGuard;
import com.akine.person.spi.AutorizacionDirectory;
import com.akine.person.spi.AutorizacionSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * El lado clinico de la derivacion de un participante grupal (RF-M28-008, RF-M10-008, RF-M11-007).
 *
 * <h2>Que valida este servicio y que NO</h2>
 *
 * <p>El reparto es la decision de la etapa y esta escrito en
 * {@code clinical.spi.DerivacionClinicaRegistry}: el modulo duenio de la participacion valida lo
 * <b>grupal</b> —que la clase y el participante son de su tenant, que hubo asistencia y que la
 * persona estuvo, que la oferta genera registro clinico— y <b>afirma</b> el resultado en
 * {@link ParticipacionDerivable}. Este servicio valida lo <b>clinico</b>: permiso, acceso
 * justificado, perfil de paciente, historia, Caso, Plan y autorizacion.
 *
 * <p>No revalida lo ajeno porque no puede: {@code clinical -> activity} cerraria el ciclo con la
 * arista que esta etapa introduce, y ArchUnit lo rechaza.
 *
 * <h2>Este servicio autoriza, y por eso el puerto que lo expone tambien</h2>
 *
 * <p>Pasa por {@link AutorizacionClinica} exactamente como {@code CasoClinicoService} y
 * {@code PlanTratamientoService}: permiso, relacion asistencial o justificacion declarada, y
 * auditoria con la via por la que se entro. Esas cuatro cosas van juntas y son privadas de este
 * paquete; hacerlas evaluar al llamador seria DP-03 implementada dos veces.
 *
 * <h2>Lo que NO hace, y es la mitad del diseño</h2>
 *
 * <p>No crea Sesiones, no abre Casos, no numera nada dentro del Caso, no consume autorizaciones y
 * no publica en el timeline. <b>Derivar no es atender:</b> 08.04 abre la puerta y 08.05 entra.
 */
@Service
public class DerivacionClinicaService {

	private static final Logger log = LoggerFactory.getLogger(DerivacionClinicaService.class);

	private final DerivacionClinicaRepositoryPort derivaciones;
	private final CasoClinicoRepositoryPort casos;
	private final PlanTratamientoRepositoryPort planes;
	private final HistoriaClinicaRepositoryPort historias;
	private final HistoriaClinicaService historiaClinicaService;
	private final AutorizacionDirectory autorizaciones;
	private final PermissionGuard permissionGuard;
	private final RelacionAsistencialProbe relaciones;
	private final AuditTrail auditTrail;
	private final ClinicalSupportAccessAuditor supportAccessAuditor;

	public DerivacionClinicaService(
			DerivacionClinicaRepositoryPort derivaciones,
			CasoClinicoRepositoryPort casos,
			PlanTratamientoRepositoryPort planes,
			HistoriaClinicaRepositoryPort historias,
			HistoriaClinicaService historiaClinicaService,
			AutorizacionDirectory autorizaciones,
			PermissionGuard permissionGuard,
			RelacionAsistencialProbe relaciones,
			AuditTrail auditTrail,
			ClinicalSupportAccessAuditor supportAccessAuditor) {

		this.derivaciones = derivaciones;
		this.casos = casos;
		this.planes = planes;
		this.historias = historias;
		this.historiaClinicaService = historiaClinicaService;
		this.autorizaciones = autorizaciones;
		this.permissionGuard = permissionGuard;
		this.relaciones = relaciones;
		this.auditTrail = auditTrail;
		this.supportAccessAuditor = supportAccessAuditor;
	}

	/**
	 * Que sabe {@code clinical} de esa participacion.
	 *
	 * <p>Es una <b>lectura clinica</b> y por lo tanto se audita, no solo se autoriza: 04.01 fijo
	 * que toda lectura clinica deja rastro, y esta dice quien tiene historia abierta y a que Casos
	 * fue derivado — que es exactamente la clase de dato del que despues hay que poder responder.
	 */
	@Transactional(readOnly = true)
	public EstadoClinicoDeParticipacion consultar(
			ActorDeDerivacion actorExterno,
			OrigenDeParticipacion origen,
			long participacionId,
			long personaId) {

		OperatingActor actor = traducir(actorExterno);
		AccesoClinico acceso = AutorizacionClinica.exigir(
				permissionGuard, relaciones, actor, PermissionCodes.HC_READ, personaId,
				actorExterno.justificacion(), "Consultar derivaciones de una participacion");

		long organizationId = actor.contextOrganizationId();
		List<DerivacionClinica> filas =
				derivaciones.findDeLaParticipacion(organizationId, origen, participacionId);

		Long historiaClinicaId = historias.buscarVigentePorPersona(organizationId, personaId)
				.map(HistoriaClinica::getId)
				.orElse(null);

		auditTrail.record(entrada(
				AuditEvents.DERIVACION_CLINICA_ACCESSED, AuditEvents.ENTITY_DERIVACION_CLINICA,
				null, actor, acceso,
				Map.of("personaId", String.valueOf(personaId),
						"origen", origen.name(),
						"participacionId", String.valueOf(participacionId),
						"derivaciones", String.valueOf(filas.size())),
				Instant.now()));

		return new EstadoClinicoDeParticipacion(
				historiaClinicaId, filas.stream().map(f -> proyectar(f, false)).toList());
	}

	/**
	 * Vincula la participacion al Caso. Idempotente por destino.
	 *
	 * <h2>El orden de las validaciones no es casual</h2>
	 *
	 * <p>Primero el acceso, despues la historia, despues el Caso y el Plan, y la autorizacion al
	 * final. El motivo: <b>asegurar la historia es una escritura</b>, y hacerla antes de saber si
	 * el Caso existe le abriria historia clinica a alguien cuya derivacion despues falla. La
	 * historia se asegura recien cuando todo lo demas ya dio bien.
	 *
	 * <p>No hay lock de nada. Es la primera operacion de esta fase que no lo necesita: no hay
	 * contador que mover, ni cupo que tomar, ni correlativo que pedir. Solo se inserta una fila que
	 * un unique protege, y por eso tampoco hace falta {@code READ_COMMITTED}.
	 */
	@Transactional
	public DerivacionSnapshot registrar(RegistroDeDerivacion registro) {
		ActorDeDerivacion actorExterno = registro.actor();
		ParticipacionDerivable participacion = registro.participacion();

		OperatingActor actor = traducir(actorExterno);
		AccesoClinico acceso = AutorizacionClinica.exigir(
				permissionGuard, relaciones, actor, PermissionCodes.HC_WRITE,
				participacion.personaId(), actorExterno.justificacion(),
				"Derivar participante al circuito clinico");

		long organizationId = actor.contextOrganizationId();

		// Primera capa de idempotencia: el doble submit se resuelve sin intentar el INSERT.
		Optional<DerivacionClinica> yaExistente = derivaciones.findVigenteAlCaso(
				organizationId, participacion.origen(), participacion.participacionId(),
				registro.casoClinicoId());
		if (yaExistente.isPresent()) {
			log.debug("Derivacion ya vigente: participacionId={} casoId={}",
					participacion.participacionId(), registro.casoClinicoId());
			return proyectar(yaExistente.get(), true);
		}

		CasoClinico caso = casos.findByIdAndOrganizationId(registro.casoClinicoId(), organizationId)
				.orElseThrow(() -> new CasoClinicoNotAccessibleException(registro.casoClinicoId()));
		if (!caso.estaActivo()) {
			// 409 y no 404: el caso existe y quien opera tiene hc:write. Lo que la pantalla tiene
			// que ofrecer es reabrirlo con motivo, que es una operacion de M10 con su propio
			// permiso — no algo que una ruta de clases pueda hacer de costado.
			throw new CasoNoActivoException(caso.getId());
		}

		Long planId = validarPlan(organizationId, registro.planTratamientoId(), caso.getId());
		Long autorizacionId = validarAutorizacion(
				organizationId, participacion.personaId(), registro.autorizacionId(),
				participacion.fechaLocal());

		// La historia se asegura RECIEN ACA: es una escritura, y abrirla antes de saber si el Caso
		// existe le crearia historia clinica a alguien cuya derivacion despues falla.
		HistoriaClinica historia = historiaClinicaService.asegurar(
				organizationId, participacion.personaId(), actor.accountId());
		if (!caso.perteneceAHistoria(historia.getId())) {
			// El caso es del tenant pero de OTRA persona. 404 y no 409: decir "ese caso no es de
			// este paciente" confirma que existe, y con ids consecutivos se enumera.
			throw new CasoClinicoNotAccessibleException(caso.getId());
		}

		Instant ahora = Instant.now();
		DerivacionClinica derivacion = DerivacionClinica.abrir(
				organizationId,
				participacion.consultorioId(),
				participacion.origen(),
				participacion.participacionId(),
				participacion.personaId(),
				historia.getId(),
				caso.getId(),
				planId,
				participacion.ofertaId(),
				participacion.requiereCasoClinico(),
				autorizacionId,
				registro.motivo(),
				ahora,
				actor.accountId());

		DerivacionClinica guardada;
		try {
			guardada = derivaciones.saveAndFlush(derivacion);
		} catch (DataIntegrityViolationException choque) {
			// Segunda capa: otro request gano la carrera entre el pre-chequeo y este INSERT. Es la
			// ventana que ningun SELECT previo cierra, y por eso las dos capas hacen falta.
			log.info("Derivacion concurrente resuelta por el unique: participacionId={} casoId={}",
					participacion.participacionId(), caso.getId());
			return derivaciones.findVigenteAlCaso(
							organizationId, participacion.origen(),
							participacion.participacionId(), caso.getId())
					.map(fila -> proyectar(fila, true))
					.orElseThrow(() -> choque);
		}

		auditTrail.record(entrada(
				AuditEvents.DERIVACION_CLINICA_CREATED, AuditEvents.ENTITY_DERIVACION_CLINICA,
				guardada.getId(), actor, acceso,
				detalles(guardada, participacion),
				ahora));
		registrarSoporte(acceso, actor, guardada, "derivar", ahora);

		return proyectar(guardada, false);
	}

	/** Deshace el vinculo, con motivo obligatorio. La fila queda. */
	@Transactional
	public DerivacionSnapshot revertir(ReversionDeDerivacion reversion) {
		OperatingActor actor = traducir(reversion.actor());
		long organizationId = exigirContexto(actor);

		DerivacionClinica derivacion = derivaciones
				.findByIdInScope(organizationId, reversion.derivacionId())
				.orElseThrow(() -> new DerivacionNotAccessibleException(reversion.derivacionId()));

		// El acceso se evalua DESPUES de resolver la fila, porque hace falta la personaId para
		// preguntar por la relacion asistencial. Un 404 por tenant ya salio arriba, asi que nadie
		// llega aca con una derivacion ajena.
		AccesoClinico acceso = AutorizacionClinica.exigir(
				permissionGuard, relaciones, actor, PermissionCodes.HC_WRITE,
				derivacion.getPersonaId(), reversion.actor().justificacion(),
				"Revertir una derivacion clinica");

		Instant ahora = Instant.now();
		if (!derivacion.revertir(reversion.motivo(), ahora, actor.accountId())) {
			// Ya estaba revertida: el reintento tras un timeout no es un error, y el segundo
			// motivo no pisa al primero. Tampoco se audita de nuevo: no ocurrio ningun hecho.
			return proyectar(derivacion, true);
		}

		DerivacionClinica guardada = derivaciones.saveAndFlush(derivacion);
		auditTrail.record(entrada(
				AuditEvents.DERIVACION_CLINICA_REVERTED, AuditEvents.ENTITY_DERIVACION_CLINICA,
				guardada.getId(), actor, acceso,
				Map.of("personaId", String.valueOf(guardada.getPersonaId()),
						"casoClinicoId", String.valueOf(guardada.getCasoClinicoId()),
						"motivoReversion", guardada.getMotivoReversion()),
				ahora));
		registrarSoporte(acceso, actor, guardada, "revertir", ahora);

		return proyectar(guardada, false);
	}

	private Long validarPlan(long organizationId, Long planId, long casoId) {
		if (planId == null) {
			return null;
		}
		PlanTratamiento plan = planes.findByIdAndOrganizationId(planId, organizationId)
				.orElseThrow(() -> new PlanTratamientoNotAccessibleException(planId));
		if (!plan.perteneceACaso(casoId)) {
			// Un plan de otro Caso es 404, no 409: el id existe y decirlo lo confirma.
			throw new PlanTratamientoNotAccessibleException(planId);
		}
		return plan.getId();
	}

	private Long validarAutorizacion(
			long organizationId, long personaId, Long autorizacionId, java.time.LocalDate fecha) {

		if (autorizacionId == null) {
			// El caso mas frecuente, no una anomalia: un paciente particular no tiene ninguna, y
			// el alcance vigente del producto es el Circuito Particular. Ver el diseño, seccion 6.
			return null;
		}
		AutorizacionSnapshot snapshot = autorizaciones
				.find(organizationId, personaId, autorizacionId, fecha)
				.orElseThrow(() -> new AutorizacionNoElegibleException(
						autorizacionId, "NO_ACCESIBLE"));
		if (!snapshot.habilita()) {
			throw new AutorizacionNoElegibleException(
					autorizacionId, snapshot.motivoNoElegible());
		}
		return snapshot.id();
	}

	private OperatingActor traducir(ActorDeDerivacion actor) {
		return new OperatingActor(
				actor.accountId(), actor.platformAdmin(),
				actor.organizationId(), actor.consultorioId());
	}

	private long exigirContexto(OperatingActor actor) {
		if (actor.contextOrganizationId() == null || actor.consultorioId() == null) {
			throw new org.springframework.security.access.AccessDeniedException(
					"La operacion requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
	}

	private static Map<String, String> detalles(
			DerivacionClinica derivacion, ParticipacionDerivable participacion) {

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("personaId", String.valueOf(derivacion.getPersonaId()));
		detalles.put("origen", participacion.origen().name());
		detalles.put("participacionId", String.valueOf(derivacion.getParticipacionId()));
		detalles.put("historiaClinicaId", String.valueOf(derivacion.getHistoriaClinicaId()));
		detalles.put("casoClinicoId", String.valueOf(derivacion.getCasoClinicoId()));
		detalles.put("ofertaId", String.valueOf(derivacion.getOfertaId()));
		detalles.put("planTratamientoId", String.valueOf(derivacion.getPlanTratamientoId()));
		detalles.put("autorizacionId", String.valueOf(derivacion.getAutorizacionId()));
		return detalles;
	}

	private AuditEntry entrada(
			String eventType,
			String entityType,
			Long entityId,
			OperatingActor actor,
			AccesoClinico acceso,
			Map<String, String> detalles,
			Instant ahora) {

		Map<String, String> conVia = new LinkedHashMap<>(detalles);
		conVia.put("viaDeAcceso", acceso.via());

		return new AuditEntry(
				actor.contextOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				eventType,
				entityType,
				entityId,
				null,
				null,
				conVia,
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora);
	}

	private void registrarSoporte(
			AccesoClinico acceso,
			OperatingActor actor,
			DerivacionClinica derivacion,
			String operacion,
			Instant ahora) {

		if (!acceso.viaSupportAccess()) {
			return;
		}
		supportAccessAuditor.record(new AuditEntry(
				derivacion.getOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				"SUPPORT_ACCESS_USED",
				AuditEvents.ENTITY_DERIVACION_CLINICA,
				derivacion.getId(),
				null,
				null,
				Map.of("operacion", operacion),
				acceso.justificacion(),
				AuditEvents.correlationId(),
				ahora));
	}

	private static DerivacionSnapshot proyectar(DerivacionClinica d, boolean yaExistia) {
		return new DerivacionSnapshot(
				d.getId(),
				d.getOrganizationId(),
				d.getConsultorioId(),
				d.getOrigen(),
				d.getParticipacionId(),
				d.getPersonaId(),
				d.getHistoriaClinicaId(),
				d.getCasoClinicoId(),
				d.getPlanTratamientoId(),
				d.getOfertaId(),
				d.isRequiereCasoClinico(),
				d.getAutorizacionId(),
				d.getEstado().name(),
				d.getMotivo(),
				d.getDerivadaEn(),
				d.getDerivadaPorCuentaId(),
				d.getRevertidaEn(),
				d.getRevertidaPorCuentaId(),
				d.getMotivoReversion(),
				d.getVersion(),
				yaExistia);
	}
}
