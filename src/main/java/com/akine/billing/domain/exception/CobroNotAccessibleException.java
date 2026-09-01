package com.akine.billing.domain.exception;

/** El cobro no existe, o es de otro tenant o de otra sede. <b>404 siempre</b>: ADR-0018. */
public class CobroNotAccessibleException extends RuntimeException {

	private final long cobroId;

	public CobroNotAccessibleException(long cobroId) {
		super("Cobro no accesible: " + cobroId);
		this.cobroId = cobroId;
	}

	public long getCobroId() {
		return cobroId;
	}
}
