package com.akine.activity.domain.exception;

/**
 * La asistencia no existe, o es de otro tenant, de otra sede o de otra clase. Se responde
 * <b>404 y nunca 403</b>: un 403 confirmaria que existe y bastaria probar ids consecutivos.
 */
public class AsistenciaNotAccessibleException extends RuntimeException {

	private final long asistenciaId;

	public AsistenciaNotAccessibleException(long asistenciaId) {
		super("La asistencia " + asistenciaId + " no existe en este alcance");
		this.asistenciaId = asistenciaId;
	}

	public long getAsistenciaId() {
		return asistenciaId;
	}
}
