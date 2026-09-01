package com.akine.billing.domain.exception;

/** La obligacion ya estaba anulada. <b>409</b>. Anular dos veces no es idempotente: es un error. */
public class ObligacionAnuladaException extends RuntimeException {

	private final Long obligacionId;

	public ObligacionAnuladaException(Long obligacionId) {
		super("La obligacion " + obligacionId + " ya estaba anulada");
		this.obligacionId = obligacionId;
	}

	public Long getObligacionId() {
		return obligacionId;
	}
}
