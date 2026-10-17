package com.akine.activity.domain.exception;

/** 404: inexistente o de otro tenant. Nunca 403: un 403 confirmaria que existe. */
public class PaseNotAccessibleException extends RuntimeException {

	private final long paseId;

	public PaseNotAccessibleException(long paseId) {
		super(String.format("El pase %s no existe en este alcance", paseId));
		this.paseId = paseId;
	}

	public long getPaseId() {
		return paseId;
	}
}
