package com.akine.scheduling.domain.exception;

/**
 * El turno tiene una atencion registrada y por eso no se puede cancelar, mover ni marcar ausente.
 * <b>409.</b>
 *
 * <p><b>Por que no se resuelve en silencio.</b> Cancelar un turno cuya Sesion ya existe dejaria un
 * registro clinico —y, desde M18, una obligacion economica— colgando de una reserva que segun la
 * agenda nunca ocurrio. DP-05 mantiene las dos maquinas separadas justamente para que ninguna
 * decida por la otra: la Sesion es la prueba de la atencion, y una transicion administrativa no la
 * puede borrar. La accion correcta la toma quien atiende, en su propia pantalla.
 *
 * <p>Es un tipo propio y no el generico de transicion invalida porque lleva a otra accion: no hay
 * nada que refrescar, hay una atencion que resolver.
 */
public class TurnoConAtencionException extends RuntimeException {

	private final long turnoId;

	public TurnoConAtencionException(long turnoId) {
		super("El turno " + turnoId + " ya tiene una atencion registrada");
		this.turnoId = turnoId;
	}

	public long getTurnoId() {
		return turnoId;
	}
}
