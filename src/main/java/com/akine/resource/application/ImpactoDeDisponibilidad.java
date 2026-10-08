package com.akine.resource.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Los turnos pendientes que un cambio de disponibilidad deja fuera de la disponibilidad efectiva
 * (RN-M05-004). Lo calcula {@link SimuladorDeImpacto}, igual para la consulta previa (A-11) que
 * para la respuesta del cambio aplicado.
 *
 * <p><b>Es el conjunto exacto dentro de la ventana, no una cota.</b> Un turno cuenta cuando la
 * disponibilidad efectiva lo cubria ANTES del cambio y no lo cubre DESPUES. Uno que cae en otro
 * bloque vigente del mismo profesional sigue cubierto y no cuenta; uno que ya estaba fuera de la
 * disponibilidad antes del cambio tampoco, porque no es este cambio el que lo deja afuera.
 *
 * @param turnosAfectados     cuantos son, aunque la lista venga recortada
 * @param primerTurnoAfectado inicio del primero, {@code null} si no hay ninguno
 * @param turnos              los primeros {@link SimuladorDeImpacto#LIMITE_DE_LISTA}, por inicio
 * @param evaluadoHasta       fecha local EXCLUSIVA hasta la que se miro; {@code null} si el cambio
 *                            no tenia ningun tramo futuro que evaluar
 */
public record ImpactoDeDisponibilidad(
		long turnosAfectados,
		Instant primerTurnoAfectado,
		List<TurnoAfectado> turnos,
		LocalDate evaluadoHasta) {

	public ImpactoDeDisponibilidad {
		turnos = turnos == null ? List.of() : List.copyOf(turnos);
	}

	/** Un turno pendiente que el cambio deja afuera. Sin datos del paciente. */
	public record TurnoAfectado(long turnoId, long membershipId, Instant inicio, Instant fin) {
	}

	public static ImpactoDeDisponibilidad ninguno(LocalDate evaluadoHasta) {
		return new ImpactoDeDisponibilidad(0L, null, List.of(), evaluadoHasta);
	}

	public boolean hayAlgo() {
		return turnosAfectados > 0;
	}
}
