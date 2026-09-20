package com.akine.billing.domain;

/**
 * Si el pago sigue vigente o fue anulado.
 *
 * <p>Dos valores y ningun borrado. Un pago anulado conserva su fila con motivo: el historial de
 * "se pago el 10 y se anulo el 12" es exactamente lo que una auditoria busca, y borrarlo dejaria
 * el egreso con un saldo que nadie puede explicar.
 */
public enum EstadoPagoEgreso {
	CONFIRMADO,
	ANULADO
}
