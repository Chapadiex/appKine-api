package com.akine.encounter.application;

import org.slf4j.MDC;

/**
 * Los tipos de evento que {@code encounter} escribe en la auditoria.
 *
 * <p>Cada modulo declara los suyos: un catalogo compartido en {@code platform} obligaria a todos
 * los modulos a depender de el. Misma decision que {@code clinical.application.AuditEvents} y
 * {@code billing.application.AuditEvents}.
 *
 * <h2>Por que esta clase no existia hasta AKINE-07.07</h2>
 *
 * <p><b>{@code encounter} no auditaba nada.</b> Ni una linea, ni una mutacion, ni una lectura:
 * {@code grep -rn "Audit" src/main/java/com/akine/encounter} no devolvia un solo resultado. Y es
 * el unico modulo que escribe evolucion clinica —{@code dolorEva}, objetivo, limitacion
 * funcional, nota de cierre— y el que la devuelve entera por {@code GET /sesiones/id}.
 *
 * <p>AKINE-04.01 fijo, para la Historia Clinica, que <b>toda lectura clinica se audita</b> y no
 * solo las mutaciones — "en una historia clinica el riesgo esta mas en quien la lee sin motivo
 * que en quien la modifica". {@code clinical} lo cumple en sus trece lecturas. La Sesion es el
 * hecho clinico por excelencia y quedo afuera de la regla que la propia etapa que la precede
 * habia establecido.
 *
 * <h2>Que se audita y que no</h2>
 *
 * <p>Los tres hechos con valor probatorio: que la atencion <b>empezo</b>, que alguien
 * <b>leyo</b> su contenido y que <b>se cerro</b>.
 *
 * <p><b>El autosave y la evaluacion no se auditan, a proposito.</b> El borrador se guarda cada
 * pocos segundos mientras dura la atencion: una fila por guardado enterraria los tres eventos
 * que importan bajo cientos que no dicen nada, y ademas el contenido final queda registrado por
 * el cierre, que es el hecho que la vuelve inmutable. Es el mismo criterio con el que
 * {@code billing} decidio no auditar el armado de un borrador de presentacion.
 *
 * <h2>Lo que este modulo sigue sin tener</h2>
 *
 * <p>La <b>justificacion declarada</b> de DP-03. {@code clinical} la exige por el header
 * {@code X-Justificacion-Acceso} y la guarda en el {@code reason} del evento; {@code encounter}
 * no la pide, asi que sus filas salen con {@code reason} nulo. Agregarla es un cambio de contrato
 * —un header obligatorio nuevo en cinco operaciones que el frontend ya consume— y queda elevado
 * como decision, no resuelto en silencio.
 */
final class AuditEvents {

	/** La atencion empezo: es el instante desde el cual hay contenido clinico que proteger. */
	static final String SESION_INICIADA = "SESION_INICIADA";

	/** Alguien leyo el contenido de una atencion. La regla de 04.01, aplicada a la Sesion. */
	static final String SESION_ACCEDIDA = "SESION_ACCEDIDA";

	/** La atencion se cerro y su contenido quedo firme. */
	static final String SESION_CERRADA = "SESION_CERRADA";

	static final String ENTITY_SESION = "Sesion";

	private static final String MDC_TRACE_ID = "traceId";

	private AuditEvents() {
	}

	static String correlationId() {
		return MDC.get(MDC_TRACE_ID);
	}
}
