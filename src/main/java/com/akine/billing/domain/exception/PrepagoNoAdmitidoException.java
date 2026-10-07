package com.akine.billing.domain.exception;

/**
 * El turno no admite que se le tome un prepago (AKINE E-6). <b>409</b>.
 *
 * <p>El turno existe en la sede pero no es de la persona que paga, o ya no es una reserva viva
 * (cancelado o ausente): cobrar por adelantado una atencion que no va a ocurrir es plata que hay
 * que devolver despues. 409 y no 400 porque el pedido era razonable cuando se compuso; lo que
 * cambio es el turno.
 */
public class PrepagoNoAdmitidoException extends RuntimeException {

	private final long turnoId;

	public PrepagoNoAdmitidoException(long turnoId, String razon) {
		super("El turno " + turnoId + " no admite un prepago: " + razon);
		this.turnoId = turnoId;
	}

	public long getTurnoId() {
		return turnoId;
	}
}
