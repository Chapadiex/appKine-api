package com.akine.scheduling.domain.exception;

/**
 * La cobertura elegida al reservar no es una cobertura financiada, activa y vigente ese dia de la
 * persona del turno. <b>404</b>, como toda referencia de otro tenant o de otra persona (ADR-0018):
 * distinguir "no existe" de "es de otro" seria un oraculo.
 */
public class CoberturaNoAccesibleException extends RuntimeException {

	private final long coberturaId;

	public CoberturaNoAccesibleException(long coberturaId) {
		super("Cobertura no accesible: " + coberturaId);
		this.coberturaId = coberturaId;
	}

	public long getCoberturaId() {
		return coberturaId;
	}
}
