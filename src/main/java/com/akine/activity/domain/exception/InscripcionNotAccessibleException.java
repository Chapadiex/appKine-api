package com.akine.activity.domain.exception;

/**
 * La inscripcion no existe, o es de otro tenant, de otra sede o de otra clase. Se responde
 * <b>404 y nunca 403</b>: un 403 confirmaria que existe y bastaria probar ids consecutivos.
 */
public class InscripcionNotAccessibleException extends RuntimeException {

	private final long inscripcionId;

	public InscripcionNotAccessibleException(long inscripcionId) {
		super("La inscripcion " + inscripcionId + " no existe en este alcance");
		this.inscripcionId = inscripcionId;
	}

	public long getInscripcionId() {
		return inscripcionId;
	}
}
