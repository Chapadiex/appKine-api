package com.akine.scheduling.domain;

/**
 * Quien cancelo un turno (TURNO - Tecnico §23).
 *
 * <p>Atributo de {@link EstadoTurno#CANCELADO} y no tres estados: la cancelacion tiene una sola
 * transicion, un solo efecto —libera el lugar— y lo unico que cambia es a quien se le atribuye.
 */
public enum OrigenDeCancelacion {

	PACIENTE,
	PROFESIONAL,
	INSTITUCION
}
