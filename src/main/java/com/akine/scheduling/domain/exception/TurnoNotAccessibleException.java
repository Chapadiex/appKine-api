package com.akine.scheduling.domain.exception;

/** El turno no existe, o es de otro tenant o de otra sede. <b>404 siempre</b>: ADR-0018. */
public class TurnoNotAccessibleException extends RuntimeException {

	private final long turnoId;

	public TurnoNotAccessibleException(long turnoId) {
		super("Turno no accesible: " + turnoId);
		this.turnoId = turnoId;
	}

	public long getTurnoId() {
		return turnoId;
	}
}
