package com.akine.scheduling.domain;

/**
 * Desde donde se inicio una reserva (TURNO - Tecnico §2).
 *
 * <p><b>Solo trazabilidad.</b> Ninguna regla de negocio lo mira: el punto de entrada determina que
 * datos ya se conocen, nunca que validaciones aplican. Si alguna vez un {@code if} pregunta por este
 * enum para decidir si un turno se puede reservar, se rompio el principio del flujo unico.
 */
public enum OrigenDeTurno {

	AGENDA,
	DISPONIBILIDAD,
	PACIENTE,
	CASO,
	PLAN,
	PROFESIONAL,
	SESION,
	LISTA_DE_ESPERA,
	TURNO_ANTERIOR
}
