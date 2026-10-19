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

	/**
	 * El paciente ya llego: recepcion abierta (E-4) o RETIRADO (U-21), la llegada consta. Se resuelve
	 * turno por turno, no en lote. Conserva el nombre publicado.
	 */
	EN_ESPERA,

	/** Tiene una Sesion registrada (DP-05): nunca se deshace por lote. */
	CON_ATENCION
}
