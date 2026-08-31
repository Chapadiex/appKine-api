package com.akine.encounter.domain.exception;

/**
 * El turno existe pero no habilita una atencion. <b>409</b>.
 *
 * <p>Los casos: el turno esta dado de baja, o es de otra sede, o no tiene profesional asignado y
 * quien intenta atender no coincide con el. El {@code motivo} viaja porque cada uno lleva a una
 * accion distinta de la pantalla.
 *
 * <p>409 y no 404: el turno existe y el recepcionista lo esta viendo en la agenda.
 */
public class TurnoNoAtendibleException extends RuntimeException {

	private final long turnoId;
	private final String motivo;

	public TurnoNoAtendibleException(long turnoId, String motivo) {
		super("El turno " + turnoId + " no habilita una atencion: " + motivo);
		this.turnoId = turnoId;
		this.motivo = motivo;
	}

	public long getTurnoId() {
		return turnoId;
	}

	public String getMotivo() {
		return motivo;
	}
}
