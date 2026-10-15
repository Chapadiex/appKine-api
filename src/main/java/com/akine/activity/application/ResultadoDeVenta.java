package com.akine.activity.application;

import java.math.BigDecimal;

/**
 * Lo que dejo una venta: el pase y <b>la deuda, sin cobro</b> (DP-06).
 *
 * @param creada {@code false} si fue un reintento idempotente que devolvio la venta existente
 */
public record ResultadoDeVenta(PaseView pase, Deuda deuda, boolean creada) {

	/** La obligacion de la venta (RF-M18-009). Se paga despues con {@code POST /cobros}. */
	public record Deuda(long obligacionId, BigDecimal importe, BigDecimal saldo, String moneda,
						String estado) {
	}
}
