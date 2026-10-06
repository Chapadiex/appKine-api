package com.akine.scheduling.domain;

/**
 * Por que un turno candidato de una operacion de serie NO se toca (AKINE E-3).
 *
 * <p>DP-04: una operacion de serie modifica unicamente turnos futuros pendientes. Todo lo demas se
 * informa, para que la pantalla pueda decir "3 se cancelan, 1 ya fue atendido".
 */
public enum MotivoDeOmision {

	/** Ya empezo: el pasado es inalterable. */
	YA_EMPEZO,

	/** Ya esta cancelado o ausente. */
	ESTADO_TERMINAL,

	/** El paciente esta en la sala: se resuelve turno por turno, no en lote. */
	EN_ESPERA,

	/** Tiene una Sesion registrada (DP-05): nunca se deshace por lote. */
	CON_ATENCION
}
