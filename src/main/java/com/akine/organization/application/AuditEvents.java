package com.akine.organization.application;

import com.akine.platform.spi.audit.AuditEntry;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Catalogo de eventos auditables del modulo y utilidades para armarlos.
 *
 * <p>Los tipos son constantes y no un enum porque {@code AuditEntry.eventType} es un
 * {@code String} en el contrato de {@code platform.spi.audit}: cada modulo declara su propio
 * catalogo sin que la plataforma tenga que conocerlos todos. Tenerlos aca evita que el mismo
 * evento se escriba de tres formas distintas en tres servicios.
 *
 * <p><b>Regla que no se negocia:</b> el mapa {@code details} nunca lleva secretos, tokens ni
 * contenido clinico. La auditoria registra QUE paso, QUIEN lo hizo y SOBRE QUE, no el
 * contenido del dato: la tabla se consulta para investigar incidentes y termina en backups.
 */
final class AuditEvents {

	/** Alta de organizacion, por endpoint administrativo o por onboarding (RF-M01-001). */
	static final String ORGANIZATION_CREATED = "ORGANIZATION_CREATED";

	/** Edicion de los datos de la organizacion. */
	static final String ORGANIZATION_UPDATED = "ORGANIZATION_UPDATED";

	/** Cambio de estado de la suscripcion (RF-M01-003). */
	static final String SUBSCRIPTION_TRANSITIONED = "SUBSCRIPTION_TRANSITIONED";

	/** Cambio de plan contratado. No es una transicion de estado. */
	static final String SUBSCRIPTION_PLAN_CHANGED = "SUBSCRIPTION_PLAN_CHANGED";

	/** Un alta rechazada por limite de plan (RF-M01-004). */
	static final String PLAN_LIMIT_REJECTED = "PLAN_LIMIT_REJECTED";

	/** Seleccion de contexto de trabajo (RF-M01-005, ADR-0009). */
	static final String CONTEXT_SELECTED = "CONTEXT_SELECTED";

	/** Alta de una sede adicional (RF-M03-001). La primera la audita ORGANIZATION_CREATED. */
	static final String CONSULTORIO_CREATED = "CONSULTORIO_CREATED";

	/** Edicion de datos, zona horaria o intervalo de una sede (RF-M03-003). */
	static final String CONSULTORIO_UPDATED = "CONSULTORIO_UPDATED";

	/**
	 * Baja logica de una sede (RF-M03-004). Motivo obligatorio.
	 *
	 * <p>{@code previousState}/{@code newState} llevan el estado DERIVADO —{@code ACTIVO} /
	 * {@code INACTIVO}—, que no existe como columna: ver {@code Consultorio}.
	 */
	static final String CONSULTORIO_DEACTIVATED = "CONSULTORIO_DEACTIVATED";

	/** Alta de una membership: la del fundador (01.01) o el alta directa por un admin (01.03). */
	static final String MEMBERSHIP_CREATED = "MEMBERSHIP_CREATED";

	/** Cambio de rol de una membership (RF-M02-004). {@code previousState}/{@code newState} = RoleCode. */
	static final String MEMBERSHIP_ROLE_CHANGED = "MEMBERSHIP_ROLE_CHANGED";

	/** Cambio de alcance: la membership pasa de una sede a otra, o a alcance organizacion. */
	static final String MEMBERSHIP_SCOPE_CHANGED = "MEMBERSHIP_SCOPE_CHANGED";

	/** Suspension temporal del vinculo (§33). Motivo obligatorio. */
	static final String MEMBERSHIP_SUSPENDED = "MEMBERSHIP_SUSPENDED";

	/** Vuelta desde SUSPENDIDA a ACTIVA. */
	static final String MEMBERSHIP_REACTIVATED = "MEMBERSHIP_REACTIVATED";

	/** Revocacion del vinculo (RN-M05-003). Motivo obligatorio. Estado terminal, sin borrado. */
	static final String MEMBERSHIP_REVOKED = "MEMBERSHIP_REVOKED";

	/** Permiso adicional otorgado a una membership (matriz §3). */
	static final String GRANT_ASSIGNED = "GRANT_ASSIGNED";

	/** Permiso adicional dado de baja. */
	static final String GRANT_REVOKED = "GRANT_REVOKED";

	/** Alta de un rol de plataforma. {@code organizationId = null}: es un evento de plataforma. */
	static final String PLATFORM_ROLE_GRANTED = "PLATFORM_ROLE_GRANTED";

	/** Baja de un rol de plataforma. {@code organizationId = null}. */
	static final String PLATFORM_ROLE_REVOKED = "PLATFORM_ROLE_REVOKED";

	/** Otorgamiento de un acceso de soporte sobre un tenant. Motivo obligatorio. */
	static final String SUPPORT_ACCESS_GRANTED = "SUPPORT_ACCESS_GRANTED";

	/** Revocacion anticipada de un acceso de soporte. */
	static final String SUPPORT_ACCESS_REVOKED = "SUPPORT_ACCESS_REVOKED";

	/**
	 * CADA operacion que un {@code PLATFORM_ADMIN} realiza dentro de un tenant amparado por un
	 * acceso de soporte vigente (matriz §7).
	 *
	 * <p>No alcanza con auditar el otorgamiento: lo que hay que poder reconstruir es que hizo
	 * mientras estuvo adentro.
	 */
	static final String SUPPORT_ACCESS_USED = "SUPPORT_ACCESS_USED";

	/**
	 * Rechazo por falta de permiso sobre un recurso que SI esta en el alcance del actor
	 * (AGENT.md §10: "auditoria de toda excepcion de permiso").
	 *
	 * <p>Los rechazos por ALCANCE —los 404— deliberadamente no se registran: ver
	 * {@code PermissionDenialAuditor}.
	 */
	static final String PERMISSION_DENIED = "PERMISSION_DENIED";

	static final String ENTITY_ORGANIZATION = "Organization";
	static final String ENTITY_SUBSCRIPTION = "Subscription";
	static final String ENTITY_MEMBERSHIP = "Membership";
	static final String ENTITY_CONSULTORIO = "Consultorio";
	static final String ENTITY_ACTIVE_CONTEXT = "AccountActiveContext";
	static final String ENTITY_MEMBERSHIP_GRANT = "MembershipGrant";
	static final String ENTITY_PLATFORM_ROLE = "PlatformRole";
	static final String ENTITY_SUPPORT_ACCESS = "SupportAccess";

	/**
	 * Entidad de los rechazos por permiso.
	 *
	 * <p>No es una tabla: el rechazo no apunta a una fila concreta, apunta a una DECISION. Usar
	 * el tipo de la entidad pedida obligaria a que el evaluador supiera sobre que recurso se
	 * estaba decidiendo, y eso lo acoplaria a cada operacion del sistema.
	 */
	static final String ENTITY_PERMISSION = "Permission";

	/** Clave con la que Micrometer Tracing publica el trace id del request en el MDC. */
	private static final String MDC_TRACE_ID = "traceId";

	private AuditEvents() {
		// Catalogo de constantes.
	}

	/**
	 * Trace id del request en curso, para correlacionar la fila de auditoria con el log
	 * estructurado (ADR-0005).
	 *
	 * <p>Se lee del MDC y no se recibe por parametro para que ningun servicio pueda olvidarse
	 * de propagarlo. Devuelve {@code null} fuera de un request —un job, un test— y eso es
	 * informacion valida: significa que el hecho no nacio de una llamada HTTP.
	 */
	static String correlationId() {
		return MDC.get(MDC_TRACE_ID);
	}

	/**
	 * Arma la entrada {@code SUPPORT_ACCESS_USED}: UNA definicion para los cinco puntos que la
	 * escriben.
	 *
	 * <p>Estaba duplicada en {@code MembershipService} y {@code ConsultorioService} con formas
	 * parecidas pero no iguales, y otros cuatro puntos de acceso directamente no la escribian.
	 * Si cada llamador arma la suya, investigar un incidente obliga a saber por cual de ellos
	 * entro cada fila — que es justo lo que la auditoria existe para no tener que adivinar.
	 *
	 * <p><b>Esta clase decide QUE se registra; cada llamador decide DONDE.</b> Una lectura la
	 * escribe por {@code SupportAccessReadAuditor} ({@code REQUIRES_NEW}, porque su transaccion
	 * es {@code readOnly} y ademas puede terminar en rollback por 404); una mutacion la escribe
	 * con el {@code AuditTrail} dentro de su propia transaccion (regla T-2). Esa diferencia NO
	 * se puede esconder aca adentro: depende de la transaccion del llamador, no del evento.
	 *
	 * @param consultorioId alcance de la decision, o {@code null} si fue de organizacion
	 * @param entityId      recurso sobre el que se ejercio el acceso, cuando hay uno concreto
	 */
	static AuditEntry usoDeSoporte(
			Long organizationId,
			Long consultorioId,
			long actorAccountId,
			String permissionCode,
			Long entityId,
			Instant ahora) {

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("permissionCode", permissionCode);
		return new AuditEntry(
				organizationId, consultorioId, actorAccountId,
				SUPPORT_ACCESS_USED, ENTITY_SUPPORT_ACCESS, entityId,
				null, null, detalles, null, correlationId(), ahora);
	}
}
