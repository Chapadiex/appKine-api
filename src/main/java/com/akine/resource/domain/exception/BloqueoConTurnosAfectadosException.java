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
 *
 * <p>Disponibilidad central (0.158.0): un cierre que alcanza a un instructor (principal o adicional) con
 * clases asignadas tambien las informa, con la misma confirmacion ({@code confirmaTurnosAfectados}).
 */
public class BloqueoConTurnosAfectadosException extends RuntimeException {

	private final long turnosAfectados;
	private final List<Turno> turnos;
	private final List<Clase> clases;

	/**
	 * Un turno afectado, tal como viaja en el 409. Es un tipo del dominio y no la vista de la capa de
	 * aplicacion porque {@code domain} no depende de nada interno (ArchUnit).
	 */
	public record Turno(long turnoId, long membershipId, Instant inicio, Instant fin, Long personaId,
			String personaNombre, String profesionalNombre, String estado) {
	}

	/** Una clase que el cierre deja sin instructor. Sin participantes. */
	public record Clase(long claseId, Long instructorMembershipId, Instant inicio, Instant fin) {
	}

	public BloqueoConTurnosAfectadosException(long turnosAfectados) {
		this(turnosAfectados, List.of());
	}

	public BloqueoConTurnosAfectadosException(long turnosAfectados, List<Turno> turnos) {
		this(turnosAfectados, turnos, List.of());
	}

	public BloqueoConTurnosAfectadosException(long turnosAfectados, List<Turno> turnos, List<Clase> clases) {
		super(mensaje(turnosAfectados, clases == null ? 0 : clases.size()));
		this.turnosAfectados = turnosAfectados;
		this.turnos = turnos == null ? List.of() : List.copyOf(turnos);
		this.clases = clases == null ? List.of() : List.copyOf(clases);
	}

	private static String mensaje(long turnos, int clases) {
		if (clases == 0) {
			return "Este bloqueo afecta " + turnos + " turnos.";
		}
		return "Este bloqueo afecta " + turnos + " turnos y " + clases + " clases.";
	}

	/** La misma, sumando las clases afectadas. */
	public BloqueoConTurnosAfectadosException conClases(List<Clase> masClases) {
		return new BloqueoConTurnosAfectadosException(turnosAfectados, turnos, masClases);
	}

	public long getTurnosAfectados() {
		return turnosAfectados;
	}

	public List<Turno> getTurnos() {
		return turnos;
	}

	public List<Clase> getClases() {
		return clases;
	}
}
