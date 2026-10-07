package com.akine.billing.domain.exception;

/**
 * El turno de un prepago no existe en la sede del cobro o es de otro tenant (AKINE E-6). <b>404</b>.
 * Inexistente y ajeno colapsan en el mismo 404: un 403 confirmaria que el id existe.
 */
public class TurnoNoAccesibleException extends RuntimeException {

	private final long turnoId;

	public TurnoNoAccesibleException(long turnoId) {
		super("El turno " + turnoId + " no existe en esta sede");
		this.turnoId = turnoId;
	}

	public long getTurnoId() {
		return turnoId;
	}
}
