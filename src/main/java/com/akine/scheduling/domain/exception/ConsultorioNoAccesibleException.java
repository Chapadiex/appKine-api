package com.akine.scheduling.domain.exception;

/** La sede no existe o es de otro tenant. <b>404 siempre</b>, nunca 403: ver ADR-0018. */
public class ConsultorioNoAccesibleException extends RuntimeException {

	private final long consultorioId;

	public ConsultorioNoAccesibleException(long consultorioId) {
		super("Consultorio no accesible: " + consultorioId);
		this.consultorioId = consultorioId;
	}

	public long getConsultorioId() {
		return consultorioId;
	}
}
