package com.akine.billing.domain.port;

import java.math.BigDecimal;

/**
 * Los totales de la cuenta corriente de un financiador, sumados por la base.
 *
 * <p>Una suma sin filas devuelve {@code NULL} en SQL; el constructor la normaliza a cero para que
 * un financiador sin lotes tenga cuenta en cero y no un {@code null} que nadie espera.
 */
public record TotalesDeCuentaCorriente(
		BigDecimal presentado,
		BigDecimal debitado,
		BigDecimal cobrado,
		BigDecimal saldo,
		long lotes) {

	private static final BigDecimal CERO = BigDecimal.ZERO.setScale(2);

	public TotalesDeCuentaCorriente {
		presentado = presentado == null ? CERO : presentado;
		debitado = debitado == null ? CERO : debitado;
		cobrado = cobrado == null ? CERO : cobrado;
		saldo = saldo == null ? CERO : saldo;
	}
}
