package com.akine.resource.application;

import org.slf4j.MDC;

/**
 * Catalogo de los tipos de evento de auditoria que emite {@code resource}.
 *
 * <p>Es un catalogo por MODULO y no uno global: {@code organization} tiene el suyo, con las
 * mismas constantes {@code static final String}, y unificarlos exigiria una clase compartida
 * que ningun modulo posee. La convencion —{@code ENTIDAD_VERBO_EN_PASADO}, mayusculas, sin
 * acentos— si es comun, y {@code audit_event.event_type} es un {@code VARCHAR(64)} sin lista
 * cerrada justamente para que cada modulo agregue los suyos sin migracion.
 *
 * <p><b>Toda operacion sensible se audita DENTRO de la transaccion del negocio</b>, nunca en un
 * listener post-commit: uno que falla deja la mutacion sin rastro. El corolario incomodo, que
 * ya costo caro en 01.03: <b>una excepcion de negocio hace rollback de todo lo escrito antes de
 * lanzarla, incluida la auditoria</b>. Por eso ningun rechazo se audita desde los servicios de
 * este modulo: los que deben quedar registrados —el permiso denegado— los escribe el evaluador
 * de {@code organization} en su propia transaccion.
 */
final class AuditEvents {

	/** Alta de un espacio (RF-M04-001). */
	static final String ESPACIO_CREATED = "ESPACIO_CREATED";

	/** Edicion de nombre, tipo, capacidad, notas o vigencia (RF-M04-002, RF-M04-007). */
	static final String ESPACIO_UPDATED = "ESPACIO_UPDATED";

	/**
	 * Baja logica de un espacio (RF-M04-006). Motivo obligatorio.
	 *
	 * <p>{@code previousState}/{@code newState} llevan el estado DERIVADO —{@code ACTIVO} /
	 * {@code INACTIVO}—, que no existe como columna: ver {@code Espacio}.
	 */
	static final String ESPACIO_DEACTIVATED = "ESPACIO_DEACTIVATED";

	static final String ENTITY_ESPACIO = "Espacio";

	/** Clave con la que Micrometer Tracing publica el trace id del request en el MDC. */
	private static final String MDC_TRACE_ID = "traceId";

	private AuditEvents() {
		// Catalogo de constantes.
	}

	/**
	 * Trace id del request en curso, para correlacionar la fila de auditoria con el log
	 * estructurado (ADR-0005).
	 *
	 * <p>Se lee del MDC y no se recibe por parametro para que ningun servicio pueda olvidarse de
	 * propagarlo. Devuelve {@code null} fuera de un request —un job, un test— y eso es
	 * informacion valida: significa que el hecho no nacio de una llamada HTTP.
	 */
	static String correlationId() {
		return MDC.get(MDC_TRACE_ID);
	}
}
