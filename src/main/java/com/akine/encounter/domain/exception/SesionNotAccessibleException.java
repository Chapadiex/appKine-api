package com.akine.encounter.domain.exception;

/** La sesion no existe, o es de otro tenant o de otra sede. <b>404 siempre</b>: ADR-0018. */
public class SesionNotAccessibleException extends RuntimeException {

	private final long sesionId;

	public SesionNotAccessibleException(long sesionId) {
		super("Sesion no accesible: " + sesionId);
		this.sesionId = sesionId;
	}

	public long getSesionId() {
		return sesionId;
	}
}
