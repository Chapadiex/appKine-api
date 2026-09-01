package com.akine.billing.domain.exception;

/** La obligacion no existe, o es de otro tenant o de otra sede. <b>404 siempre</b>: ADR-0018. */
public class ObligacionNotAccessibleException extends RuntimeException {

	private final long obligacionId;

	public ObligacionNotAccessibleException(long obligacionId) {
		super("Obligacion no accesible: " + obligacionId);
		this.obligacionId = obligacionId;
	}

	public long getObligacionId() {
		return obligacionId;
	}
}
