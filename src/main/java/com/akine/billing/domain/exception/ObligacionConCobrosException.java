package com.akine.billing.domain.exception;

import java.math.BigDecimal;

/**
 * Se intenta anular una deuda que ya tiene cobros imputados. <b>409</b>.
 *
 * <p>Anular lo que ya se cobro dejaria plata en la caja sin ninguna deuda que la justifique, y el
 * arqueo del dia no cerraria. Lo que corresponde es una devolucion, que es M19 y tiene su propio
 * registro con su propio movimiento.
 *
 * <p>El importe ya cobrado viaja para que la pantalla pueda ofrecer la accion correcta en vez de
 * dejar al administrativo adivinando por que no lo deja.
 */
public class ObligacionConCobrosException extends RuntimeException {

	private final Long obligacionId;
	private final BigDecimal yaCobrado;

	public ObligacionConCobrosException(Long obligacionId, BigDecimal yaCobrado) {
		super("La obligacion " + obligacionId + " ya tiene " + yaCobrado
				+ " cobrado: anularla exige una devolucion");
		this.obligacionId = obligacionId;
		this.yaCobrado = yaCobrado;
	}

	public Long getObligacionId() {
		return obligacionId;
	}

	public BigDecimal getYaCobrado() {
		return yaCobrado;
	}
}
