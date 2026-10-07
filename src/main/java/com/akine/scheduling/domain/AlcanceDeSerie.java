package com.akine.scheduling.domain;

/**
 * Sobre que turnos de una serie actua una cancelacion o una reprogramacion (AKINE E-3).
 *
 * <p>Define los <b>candidatos</b>. De ellos solo se tocan los pendientes: ver
 * {@link MotivoDeOmision} para los que se dejan como estan.
 */
public enum AlcanceDeSerie {

	/** Solo el turno pivote. */
	ESTE,

	/** El pivote y los de la serie que empiezan despues, por su horario ACTUAL. */
	ESTE_Y_SIGUIENTES,

	/** Todos los turnos de la serie. Los pasados igual se omiten: DP-04 los declara inalterables. */
	TODA_LA_SERIE
}
