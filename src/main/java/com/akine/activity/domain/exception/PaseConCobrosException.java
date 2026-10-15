package com.akine.activity.domain.exception;

import java.math.BigDecimal;

/**
 * 409 {@code pase-con-cobros}: la deuda de la venta tiene cobros imputados, asi que el pase no se
 * anula. Es la regla de {@code billing} —una deuda cobrada no se anula—: lo que corresponde es
 * anular o reintegrar el cobro (RF-M19-010), un acto de caja con su propio registro, y despues
 * anular el pase. Nada se escribe.
 */
public class PaseConCobrosException extends RuntimeException {

	private final long paseId;
	private final long obligacionId;
	private final BigDecimal cobrado;

	public PaseConCobrosException(long paseId, long obligacionId, BigDecimal cobrado) {
		super("El pase " + paseId + " no se puede anular: su deuda " + obligacionId
				+ " tiene cobros imputados por " + cobrado.toPlainString());
		this.paseId = paseId;
		this.obligacionId = obligacionId;
		this.cobrado = cobrado;
	}

	public long getPaseId() {
		return paseId;
	}

	public long getObligacionId() {
		return obligacionId;
	}

	public BigDecimal getCobrado() {
		return cobrado;
	}
}
