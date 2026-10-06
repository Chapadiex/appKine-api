package com.akine.billing.domain;

/**
 * De donde salio el movimiento.
 *
 * <p>Junto con {@code referencia_origen} forma la mitad del unique que hace idempotente el
 * reintento de un cobro y que impide revertir dos veces el mismo movimiento.
 */
public enum OrigenMovimiento {

	/**
	 * Lo genero un cobro (M19), en su misma transaccion.
	 *
	 * <p>{@code referencia_origen} es el id del cobro. Un cobro con dos medios produce dos
	 * movimientos: la relacion cobro-movimiento <b>no es uno a uno</b>, y por eso el unique lleva
	 * tambien el medio.
	 */
	COBRO,

	/**
	 * Lo cargo una persona: RF-M20-002 (ingreso autorizado no originado automaticamente) y
	 * RF-M20-003 (salida de dinero).
	 *
	 * <p>{@code referencia_origen} queda en NULL, y <b>varios NULL no colisionan en MySQL</b>, que
	 * es justo lo que se quiere: dos ingresos manuales del mismo dia son dos hechos distintos. La
	 * idempotencia de estos la da su propia {@code idempotency_key}, igual que en {@code cobro}.
	 */
	MANUAL,

	/**
	 * Lo genero el pago de un egreso (M22), en su misma transaccion.
	 *
	 * <p>{@code referencia_origen} es el id del {@code pago_egreso}. <b>Un pago tiene un solo
	 * medio</b> —pagarle a un profesional mitad en efectivo y mitad por transferencia son dos
	 * hechos distintos, con dos comprobantes—, asi que el unique de V54 garantiza gratis que un
	 * pago produzca a lo sumo un movimiento y que el reintento no duplique la salida de plata.
	 *
	 * <p>Es la razon por la que {@code pago_egreso} <b>no tiene</b> {@code movimiento_caja_id}: es
	 * el movimiento el que apunta al pago, igual que apunta al cobro.
	 */
	PAGO_EGRESO,

	/**
	 * Compensa un movimiento anterior. {@code referencia_origen} es el id del movimiento original.
	 *
	 * <p>Con {@code tipo} dentro de la clave unica, un movimiento se revierte <b>una sola vez</b> y
	 * la reversion puede convivir con el movimiento que compensa. Mismo mecanismo que V50.
	 */
	REVERSION,

	/**
	 * Lo genero un pago de financiador (M21), en su misma transaccion. AKINE-07.04.
	 *
	 * <p>{@code referencia_origen} es el id del {@code FinanciadorPago}. <b>No se reuso
	 * {@link #COBRO}</b> por dos motivos: el unique
	 * {@code (organization_id, tipo, tipo_origen, referencia_origen, medio)} colisionaria entre un
	 * cobro y un pago que casualmente compartan id, y un ledger que no distingue <b>quien</b> pago
	 * no sirve para explicar de donde salio la plata — que es lo unico que un ledger tiene que
	 * poder hacer.
	 *
	 * <p>Un pago por transferencia asienta su movimiento con {@code jornada_caja_id} NULL y
	 * {@code afecta_arqueo = 0}: esa plata fue a un banco, no al cajon. Es la regla de 07.03
	 * aplicada sin excepcion. Si el financiador paga en efectivo —raro pero legitimo— rige la otra
	 * mitad de esa regla y hace falta jornada abierta.
	 */
	PAGO_FINANCIADOR,

	/**
	 * Lo genero el reintegro de un saldo a favor (M19, F-3), en su misma transaccion.
	 *
	 * <p>{@code referencia_origen} es el id del {@code cobro_reintegro}, y el movimiento es un
	 * {@code EGRESO}: la plata sale del cajon. <b>No es una {@link #REVERSION}</b>, que compensa un
	 * movimiento entero y una sola vez; un reintegro puede ser parcial, repetirse y salir por otro
	 * medio que el que entro.
	 */
	REINTEGRO
}
