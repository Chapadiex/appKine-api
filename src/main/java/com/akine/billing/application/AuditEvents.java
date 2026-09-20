package com.akine.billing.application;

import org.slf4j.MDC;

/**
 * Los tipos de evento que {@code billing} escribe en la auditoria.
 *
 * <p>Cada modulo declara los suyos: un catalogo compartido en {@code platform} obligaria a todos los
 * modulos a depender de el. Misma decision que {@code clinical.application.AuditEvents}.
 *
 * <p>Se auditan las <b>mutaciones de caja</b> —apertura, cierre y compensacion—, que es lo que la
 * etapa declara sensible: son los tres puntos donde una persona decide algo sobre dinero real. El
 * movimiento que nace de un cobro no lleva evento propio porque el cobro ya es el hecho auditable, y
 * duplicarlo haria que la auditoria contara dos veces la misma operacion.
 */
final class AuditEvents {

	static final String CAJA_ABIERTA = "CAJA_ABIERTA";
	static final String CAJA_CERRADA = "CAJA_CERRADA";
	static final String CAJA_MOVIMIENTO_MANUAL = "CAJA_MOVIMIENTO_MANUAL";
	static final String CAJA_MOVIMIENTO_REVERTIDO = "CAJA_MOVIMIENTO_REVERTIDO";

	/**
	 * Egresos y pagos a profesionales (M22).
	 *
	 * <p>Se auditan las cinco mutaciones y <b>no la consulta</b>: no es acceso clinico —donde toda
	 * lectura se audita por DP-03— y el volumen de un listado la volveria ruido que tapa lo que
	 * importa.
	 */
	static final String EGRESO_REGISTRADO = "EGRESO_REGISTRADO";
	static final String EGRESO_CONFIRMADO = "EGRESO_CONFIRMADO";
	static final String EGRESO_ANULADO = "EGRESO_ANULADO";
	static final String EGRESO_PAGADO = "EGRESO_PAGADO";
	static final String EGRESO_PAGO_ANULADO = "EGRESO_PAGO_ANULADO";

	static final String ENTITY_JORNADA_CAJA = "JornadaCaja";
	static final String ENTITY_MOVIMIENTO_CAJA = "MovimientoCaja";
	static final String ENTITY_EGRESO = "Egreso";
	static final String ENTITY_PAGO_EGRESO = "PagoEgreso";

	private static final String MDC_TRACE_ID = "traceId";

	private AuditEvents() {
	}

	static String correlationId() {
		return MDC.get(MDC_TRACE_ID);
	}
}
