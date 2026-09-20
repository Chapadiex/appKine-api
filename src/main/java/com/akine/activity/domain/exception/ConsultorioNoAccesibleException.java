package com.akine.activity.domain.exception;

/**
 * La sede no existe o es de otro tenant. <b>404 y nunca 403</b>: un 403 confirmaria su existencia
 * y bastaria probar ids consecutivos para enumerar las sedes de la competencia (ADR-0018).
 */
public class ConsultorioNoAccesibleException extends RuntimeException {

	private final long consultorioId;

	public ConsultorioNoAccesibleException(long consultorioId) {
		super("El consultorio " + consultorioId + " no existe en este tenant");
		this.consultorioId = consultorioId;
	}

	public long getConsultorioId() {
		return consultorioId;
	}
}
