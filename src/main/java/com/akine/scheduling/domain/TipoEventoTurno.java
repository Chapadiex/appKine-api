package com.akine.scheduling.domain;

/**
 * Que clase de transicion registra un {@link TurnoEvento}.
 *
 * <p>Es distinto del estado destino y por eso existe: {@code RESERVA} y {@code REPROGRAMACION}
 * terminan las dos en {@code RESERVADO}, y sin el tipo el historial mostraria dos filas iguales
 * para dos hechos que no tienen nada que ver.
 */
public enum TipoEventoTurno {

	/** El turno nacio. Es el unico evento cuyo estado anterior es {@code null}. */
	RESERVA,

	/** La reserva se confirmo. */
	CONFIRMACION,

	/** La reserva se deshizo con motivo. Libera el lugar. */
	CANCELACION,

	/** El turno se movio a otro intervalo. Lleva el intervalo anterior y el nuevo. */
	REPROGRAMACION,

	/** El paciente no vino. No libera el lugar. */
	AUSENCIA
}
