package com.akine.resource.domain.exception;

/**
 * El cierre deja afuera turnos pendientes y no se confirmo (TURNO.md §22). <b>409</b>, con la cuenta.
 *
 * <p>Quien sabe lo que hace lo vuelve a pedir con la confirmacion: lo que no puede pasar es que un
 * bloqueo deje turnos en conflicto sin que nadie lo haya visto.
 */
public class BloqueoConTurnosAfectadosException extends RuntimeException {

	private final long turnosAfectados;

	public BloqueoConTurnosAfectadosException(long turnosAfectados) {
		super("Este bloqueo afecta " + turnosAfectados + " turnos.");
		this.turnosAfectados = turnosAfectados;
	}

	public long getTurnosAfectados() {
		return turnosAfectados;
	}
}
