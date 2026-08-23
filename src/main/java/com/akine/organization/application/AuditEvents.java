package com.akine.organization.application;

import org.slf4j.MDC;

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

	/** Alta de una membership. En 01.01 solo la del fundador. */
	static final String MEMBERSHIP_CREATED = "MEMBERSHIP_CREATED";

	static final String ENTITY_ORGANIZATION = "Organization";
	static final String ENTITY_SUBSCRIPTION = "Subscription";
	static final String ENTITY_MEMBERSHIP = "Membership";
	static final String ENTITY_ACTIVE_CONTEXT = "AccountActiveContext";

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
}
