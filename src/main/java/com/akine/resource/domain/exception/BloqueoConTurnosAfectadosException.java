package com.akine.resource.domain.exception;

import java.time.Instant;
import java.util.List;

/**
 * El cambio de disponibilidad deja afuera turnos pendientes y no se confirmo (TURNO.md §22). <b>409</b>,
 * con la cuenta y la lista de los afectados.
 *
 * <p>Quien sabe lo que hace lo vuelve a pedir con la confirmacion: lo que no puede pasar es que un
 * bloqueo deje turnos en conflicto sin que nadie lo haya visto. Lo usan el alta y la edicion de una
 * excepcion y la edicion y la baja de un bloque del horario habitual.
 */
public class BloqueoConTurnosAfectadosException extends RuntimeException {

	private final long turnosAfectados;
	private final List<Turno> turnos;

	/**
	 * Un turno afectado, tal como viaja en el 409. Es un tipo del dominio y no la vista de la capa de
	 * aplicacion porque {@code domain} no depende de nada interno (ArchUnit).
	 */
	public record Turno(long turnoId, long membershipId, Instant inicio, Instant fin, Long personaId,
			String personaNombre, String profesionalNombre, String estado) {
	}

	public BloqueoConTurnosAfectadosException(long turnosAfectados) {
		this(turnosAfectados, List.of());
	}

	public BloqueoConTurnosAfectadosException(long turnosAfectados, List<Turno> turnos) {
		super("Este bloqueo afecta " + turnosAfectados + " turnos.");
		this.turnosAfectados = turnosAfectados;
		this.turnos = turnos == null ? List.of() : List.copyOf(turnos);
	}

	public long getTurnosAfectados() {
		return turnosAfectados;
	}

	public List<Turno> getTurnos() {
		return turnos;
	}
}
