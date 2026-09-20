package com.akine.encounter.application;

import org.slf4j.MDC;

/**
 * Catalogo de eventos de auditoria que emite {@code encounter}.
 *
 * <h2>Por que aparece recien en 06.06</h2>
 *
 * <p>Iniciar, guardar el borrador, evaluar y cerrar dejan su rastro en la propia fila: quien
 * inicio, quien cerro, cuando, y una version que avanza con cada escritura. La enmienda no: es la
 * <b>unica operacion del modulo que cambia lo que un registro clinico ya cerrado dice</b>, y de
 * ella no queda nada en {@code sesion} salvo el contenido nuevo. El rastro tiene que ser explicito
 * o RN-M14-006 —"no se modifica silenciosamente"— queda escrito y no cumplido.
 *
 * <h2>Lo que NO va a la auditoria</h2>
 *
 * <p>Ni el contenido clinico ni el motivo de la enmienda. El motivo es prosa que el profesional
 * escribe sobre un paciente —"se corrigio la lateralidad: era rodilla izquierda"— y
 * {@code audit_event} se consulta con {@code auditoria:read}, que <b>no es un permiso clinico</b>:
 * copiarlo ahi convertiria la auditoria en una via de lectura clinica sin permiso clinico. El
 * motivo vive en {@code sesion_version.motivo_enmienda}, detras del mismo permiso que el resto del
 * registro. Es la misma decision que tomo {@code clinical} en 04.02.
 *
 * <p>Lo que si va es <b>que paso, quien lo hizo y sobre que</b>: la transicion de numero de
 * version es suficiente para reconstruir el historial y llevar a quien investiga a la fila que
 * tiene el detalle.
 */
public final class AuditEvents {

	/** Clave del identificador de correlacion en el MDC, puesta por el filtro de request. */
	private static final String MDC_TRACE_ID = "traceId";

	/** Se escribio una version nueva de una sesion cerrada (RF-M14-010). */
	public static final String SESION_AMENDED = "SESION_AMENDED";

	/** Tipo de entidad de los eventos de este modulo. */
	public static final String ENTITY_SESION = "SESION";

	private AuditEvents() {
	}

	/**
	 * Correlaciona el evento con el log estructurado del request (ADR-0005).
	 *
	 * <p>Sale del MDC y puede ser {@code null} fuera de un request —un job, un test—, que es
	 * informacion legitima y no un descuido.
	 */
	static String correlationId() {
		return MDC.get(MDC_TRACE_ID);
	}
}
