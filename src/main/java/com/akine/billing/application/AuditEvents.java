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
	 * Los siete hechos de M21 que cambian lo que el centro le reclama a un financiador.
	 *
	 * <p><b>Agregar y quitar items de un borrador no se audita</b>: el borrador todavia no es un
	 * hecho —no tiene numero, nadie lo vio, no existio para el financiador— y auditar cada clic del
	 * armado enterraria los siete eventos que si importan bajo cientos que no dicen nada.
	 */
	static final String PRESENTACION_CREADA = "PRESENTACION_CREADA";
	static final String PRESENTACION_CONFIRMADA = "PRESENTACION_CONFIRMADA";
	static final String PRESENTACION_FACTURADA = "PRESENTACION_FACTURADA";
	static final String PRESENTACION_ITEM_DEBITADO = "PRESENTACION_ITEM_DEBITADO";
	static final String PRESENTACION_PAGO_REGISTRADO = "PRESENTACION_PAGO_REGISTRADO";
	static final String PRESENTACION_CONCILIADA = "PRESENTACION_CONCILIADA";
	static final String PRESENTACION_ANULADA = "PRESENTACION_ANULADA";

	static final String ENTITY_JORNADA_CAJA = "JornadaCaja";
	static final String ENTITY_MOVIMIENTO_CAJA = "MovimientoCaja";
	static final String ENTITY_PRESENTACION = "Presentacion";

	private static final String MDC_TRACE_ID = "traceId";

	private AuditEvents() {
	}

	static String correlationId() {
		return MDC.get(MDC_TRACE_ID);
	}
}
