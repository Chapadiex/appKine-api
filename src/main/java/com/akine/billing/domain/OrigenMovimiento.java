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
	 * Compensa un movimiento anterior. {@code referencia_origen} es el id del movimiento original.
	 *
	 * <p>Con {@code tipo} dentro de la clave unica, un movimiento se revierte <b>una sola vez</b> y
	 * la reversion puede convivir con el movimiento que compensa. Mismo mecanismo que V50.
	 */
	REVERSION
}
