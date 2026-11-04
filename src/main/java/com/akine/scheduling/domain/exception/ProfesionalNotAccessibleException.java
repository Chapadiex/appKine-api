package com.akine.scheduling.domain.exception;

/**
 * El profesional no existe en la organizacion del contexto, o la organizacion pedida no es la del
 * contexto. <b>404 siempre</b>: un 403 confirmaria que existe (ADR-0018).
 */
public class ProfesionalNotAccessibleException extends RuntimeException {

	private final long profesionalId;

	public ProfesionalNotAccessibleException(long profesionalId) {
		super("Profesional no accesible: " + profesionalId);
		this.profesionalId = profesionalId;
	}

	public long getProfesionalId() {
		return profesionalId;
	}
}
