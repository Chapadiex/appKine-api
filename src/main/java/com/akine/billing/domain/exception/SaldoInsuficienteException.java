package com.akine.billing.domain.exception;

import java.math.BigDecimal;

/**
 * Se intenta imputar mas de lo que la deuda debe. <b>409</b>.
 *
 * <p>Es el desenlace legitimo de una carrera: entre que la pantalla mostro la cuenta corriente y el
 * operador confirmo, otro cobro pudo llevarse esa plata. El descuento se hace con un UPDATE
 * condicional —{@code SET saldo = saldo - :importe WHERE saldo >= :importe}— que es atomico y no
 * puede dejar el saldo en negativo; cuando afecta cero filas, llega aca.
 *
 * <p>409 y no 400: el cuerpo enviado era valido cuando se compuso, y lo que cambio es el estado del
 * servidor. Reintentar con la cuenta corriente recargada es la accion correcta.
 */
public class SaldoInsuficienteException extends RuntimeException {

	private final long obligacionId;
	private final BigDecimal importeIntentado;

	public SaldoInsuficienteException(long obligacionId, BigDecimal importeIntentado) {
		super("La obligacion " + obligacionId + " no tiene saldo suficiente para imputar "
				+ importeIntentado + ": otro cobro pudo haberse aplicado antes");
		this.obligacionId = obligacionId;
		this.importeIntentado = importeIntentado;
	}

	public long getObligacionId() {
		return obligacionId;
	}

	public BigDecimal getImporteIntentado() {
		return importeIntentado;
	}
}
