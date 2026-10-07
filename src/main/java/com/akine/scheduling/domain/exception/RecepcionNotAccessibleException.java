package com.akine.scheduling.domain.exception;

/** El turno existe pero no tiene recepcion vigente: nadie registro la llegada (404). */
public class RecepcionNotAccessibleException extends RuntimeException {

	private final long turnoId;

	public RecepcionNotAccessibleException(long turnoId) {
		super("El turno " + turnoId + " no tiene recepcion");
		this.turnoId = turnoId;
	}

	public long getTurnoId() {
		return turnoId;
	}
}
