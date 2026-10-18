package com.akine.encounter.application;

import com.akine.clinical.spi.ActorDeDerivacion;
import com.akine.clinical.spi.CasoDirectory;
import com.akine.clinical.spi.DerivacionClinicaRegistry;
import com.akine.clinical.spi.DerivacionSnapshot;
import com.akine.clinical.spi.OrigenDeParticipacion;
import com.akine.encounter.domain.PermissionCodes;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.exception.CasoNoAsignableException;
import com.akine.encounter.domain.exception.ConsultorioNoAccesibleException;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.encounter.spi.ActorDeAtencion;
import com.akine.encounter.spi.AtencionDeParticipante;
import com.akine.encounter.spi.PedidoDeAtencion;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * La atencion clinica de cada participante de una clase (AKINE-08.05, RF-M14-011..013).
 *
 * <h2>Reusa la Sesion, no la duplica</h2>
 *
 * <p>Este servicio solo <b>abre</b> la atencion desde una derivacion y la lista para el panel. Todo
 * lo demas —borrador, evaluacion, tratamientos, mediciones, cierre con sus dos numeradores,
 * enmienda, lectura auditada— es {@link SesionService} sin cambios, por las rutas de siempre. Por
 * eso cerrar la atencion de Maria no cierra la de Juan (CA-M14-013-06) y dos pacientes de la misma
 * clase tienen numeros independientes segun sus Casos (CA-M14-012-06): son dos Sesiones.
 *
 * <h2>El orden de los gates</h2>
 *
 * <pre>
 *   1. contexto, sede del tenant, sesion:register, membership en la sede
 *   2. clinical: hc:write + relacion o justificacion, derivacion vigente (auditado alla)
 *   3. idempotencia: la Sesion viva de esa derivacion se devuelve tal cual
 *   4. el Caso de la derivacion sigue activo y es de su historia
 *   5. INSERT + auditoria SESION_INICIADA, en la transaccion del llamador
 * </pre>
 *
 * <p>La idempotencia va <b>despues</b> del acceso clinico y no antes, al reves que el inicio desde
 * un turno: devolver una Sesion existente a quien no tiene acceso clinico a ese paciente le diria
 * que ese paciente esta en tratamiento. Y va antes del Caso: una atencion ya abierta no se niega
 * porque el Caso se cerro despues, mismo criterio que el cierre de {@link SesionService}.
 *
 * <p><b>La serializacion la pone el llamador</b>: {@code activity} toma la fila de la asistencia
 * con {@code FOR UPDATE} en {@code READ_COMMITTED} antes de llamar, igual que al derivar, y este
 * metodo se une a esa transaccion. El {@code uk_sesion_derivacion} de V102 es el respaldo.
 */
@Service
public class AtencionGrupalService {

	private static final Logger log = LoggerFactory.getLogger(AtencionGrupalService.class);

	static final String ORIGEN = OrigenDeParticipacion.CLASE_PROGRAMADA.name();

	private final SesionRepositoryPort sesiones;
	private final DerivacionClinicaRegistry derivaciones;
	private final CasoDirectory casos;
	private final ConsultorioDirectory consultorios;
	private final ConsultorioMembershipDirectory memberships;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public AtencionGrupalService(
			SesionRepositoryPort sesiones,
			DerivacionClinicaRegistry derivaciones,
			CasoDirectory casos,
			ConsultorioDirectory consultorios,
			ConsultorioMembershipDirectory memberships,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.sesiones = sesiones;
		this.derivaciones = derivaciones;
		this.casos = casos;
		this.consultorios = consultorios;
		this.memberships = memberships;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	/** Ver {@code encounter.spi.AtencionGrupal#iniciar} y la cabecera de la clase. */
	@Transactional
	public AtencionDeParticipante iniciar(PedidoDeAtencion pedido) {
		ActorDeAtencion actor = pedido.actor();
		long organizationId = exigirContexto(actor);
		long consultorioId = actor.consultorioId();
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);
		long profesional = membershipDe(actor, organizationId, consultorioId);

		DerivacionSnapshot derivacion = derivaciones.paraAtender(
				new ActorDeDerivacion(actor.accountId(), actor.platformAdmin(), organizationId,
						consultorioId, actor.justificacion()),
				OrigenDeParticipacion.CLASE_PROGRAMADA,
				pedido.participacionId(),
				pedido.personaId(),
				pedido.derivacionId());

		var existente = sesiones.findVivaPorDerivacion(organizationId, derivacion.id());
		if (existente.isPresent()) {
			return vista(existente.get(), true);
		}

		exigirCasoActivo(organizationId, derivacion);

		Instant ahora = Instant.now();
		Sesion sesion = sesiones.saveAndFlush(Sesion.desdeParticipacion(
				organizationId,
				consultorioId,
				derivacion.historiaClinicaId(),
				derivacion.casoClinicoId(),
				derivacion.ofertaId(),
				profesional,
				ahora,
				actor.accountId(),
				derivacion.id(),
				pedido.participacionId(),
				pedido.claseId()));

		auditar(organizationId, consultorioId, actor, AuditEvents.SESION_INICIADA, sesion.getId(),
				"ABIERTA",
				Map.of("historiaClinicaId", String.valueOf(derivacion.historiaClinicaId()),
						"casoId", String.valueOf(derivacion.casoClinicoId()),
						"origen", ORIGEN,
						"claseId", String.valueOf(pedido.claseId()),
						"derivacionId", String.valueOf(derivacion.id())),
				ahora);

		log.info("Atencion grupal iniciada: sesionId={} claseId={} derivacionId={} casoId={} "
						+ "profesional={}",
				sesion.getId(), pedido.claseId(), derivacion.id(), derivacion.casoClinicoId(),
				profesional);
		return vista(sesion, false);
	}

	/**
	 * Las atenciones de la clase, sin contenido clinico. Auditada: la lista dice quienes estan en
	 * tratamiento, y eso es informacion clinica aunque no lleve una sola nota.
	 *
	 * <p>No es {@code readOnly}: el INSERT de auditoria se une a esta transaccion.
	 */
	@Transactional
	public List<AtencionDeParticipante> deLaClase(ActorDeAtencion actor, long claseId) {
		long organizationId = exigirContexto(actor);
		long consultorioId = actor.consultorioId();
		exigirSedeDelTenant(organizationId, consultorioId);
		exigirRegistro(actor, organizationId, consultorioId);

		List<AtencionDeParticipante> atenciones =
				sesiones.findVivasDeLaClase(organizationId, consultorioId, claseId).stream()
						.map(sesion -> vista(sesion, false))
						.toList();

		auditar(organizationId, consultorioId, actor, AuditEvents.SESION_GRUPAL_CONSULTADA, null,
				null,
				Map.of("origen", ORIGEN,
						"claseId", String.valueOf(claseId),
						"sesiones", String.valueOf(atenciones.size()),
						// AKINE-09.02: que historias quedaron expuestas. Sin esto la fila no aparecia
						// al preguntar "quien accedio a la HC X": decia cuantas, no de quienes.
						"historiasClinicas", atenciones.stream()
								.map(a -> String.valueOf(a.historiaClinicaId()))
								.distinct()
								.collect(Collectors.joining(","))),
				Instant.now());
		return atenciones;
	}

	// =================================================================================

	/**
	 * El Caso de la derivacion tiene que seguir activo y colgar de su historia. Se pregunta a
	 * {@code clinical.spi.CasoDirectory} —04.03— y el rechazo es el mismo que al iniciar desde un
	 * turno: cerrado es 409 (reabrirlo), inexistente o ajeno es 404.
	 */
	private void exigirCasoActivo(long organizationId, DerivacionSnapshot derivacion) {
		long casoId = derivacion.casoClinicoId();
		var caso = casos.find(organizationId, casoId)
				.orElseThrow(() -> new CasoNoAsignableException(
						casoId, CasoNoAsignableException.Motivo.NO_ACCESIBLE));
		if (!caso.perteneceAHistoria(derivacion.historiaClinicaId())) {
			throw new CasoNoAsignableException(casoId, CasoNoAsignableException.Motivo.DE_OTRA_HISTORIA);
		}
		if (!caso.activo()) {
			throw new CasoNoAsignableException(casoId, CasoNoAsignableException.Motivo.CERRADO);
		}
	}

	private static AtencionDeParticipante vista(Sesion sesion, boolean yaExistia) {
		return new AtencionDeParticipante(
				sesion.getId(),
				sesion.getConsultorioId(),
				sesion.getClaseId(),
				sesion.getParticipacionId(),
				sesion.getDerivacionId(),
				sesion.getHistoriaClinicaId(),
				sesion.getCasoId(),
				sesion.getProfesionalMembershipId(),
				sesion.getEstado().name(),
				sesion.getNumeroSesion(),
				sesion.getNumeroEnCaso(),
				sesion.getIniciadaEn(),
				sesion.getCerradaEn(),
				sesion.getVersion(),
				yaExistia);
	}

	/** Detalles con ids y banderas, nunca contenido clinico; el prefijo SESION_ se redacta. */
	private void auditar(
			long organizationId, long consultorioId, ActorDeAtencion actor, String eventType,
			Long sesionId, String estadoNuevo, Map<String, String> detalles, Instant ahora) {

		auditTrail.record(new AuditEntry(
				organizationId,
				consultorioId,
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_SESION,
				sesionId,
				null,
				estadoNuevo,
				detalles,
				null,
				AuditEvents.correlationId(),
				ahora));
	}

	/** Falta de contexto es 403 y nunca 401: un 401 deja al frontend en bucle de login. */
	private static long exigirContexto(ActorDeAtencion actor) {
		if (actor == null || actor.organizationId() == null || actor.consultorioId() == null) {
			throw new AccessDeniedException("La atencion requiere un contexto de trabajo activo");
		}
		return actor.organizationId();
	}

	private void exigirSedeDelTenant(long organizationId, long consultorioId) {
		consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private void exigirRegistro(ActorDeAtencion actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PermissionCodes.SESION_REGISTER, organizationId, consultorioId,
				null, Instant.now()));
	}

	/** Quien abre la atencion es su profesional: la membership, no la cuenta (06.01). */
	private long membershipDe(ActorDeAtencion actor, long organizationId, long consultorioId) {
		return memberships.findByAccount(organizationId, actor.accountId()).stream()
				.filter(ConsultorioMembershipSnapshot::active)
				.filter(membership -> membership.cubreConsultorio(consultorioId))
				.map(ConsultorioMembershipSnapshot::membershipId)
				.findFirst()
				.orElseThrow(() -> new AccessDeniedException(
						"La cuenta no tiene un vinculo activo con la sede " + consultorioId));
	}
}
