package com.akine.activity.domain.exception;

/** La clase no existe, o es de otro tenant o de otra sede. Se responde <b>404 y nunca 403</b>. */
public class ClaseNotAccessibleException extends RuntimeException {

	private final long claseId;

	public ClaseNotAccessibleException(long claseId) {
		super("La clase " + claseId + " no existe en este alcance");
		this.claseId = claseId;
	}

	public long getClaseId() {
		return claseId;
	}
}
