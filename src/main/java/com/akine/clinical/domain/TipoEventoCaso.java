package com.akine.clinical.domain;

/**
 * Las transiciones que quedan asentadas en el historial de un Caso Clinico (RF-M10-006).
 *
 * <p>El historial es <b>append-only</b>: un historial que se puede editar no es un historial.
 * Mismo criterio que {@code turno_evento} en 05.03.
 */
public enum TipoEventoCaso {

	/** El caso se abrio. Es el unico evento sin estado anterior: antes no habia estado. */
	APERTURA,

	/** Cambio el diagnostico presuntivo o el objetivo terapeutico. No cambia el estado. */
	EDICION,

	/** El caso se cerro. <b>Exige motivo</b>: sin el, un cierre es indistinguible de un abandono. */
	CIERRE,

	/** El caso volvio a ACTIVO. Exige motivo por lo mismo que el cierre. */
	REAPERTURA,

	/** Entro o salio alguien del equipo tratante. No cambia el estado. */
	CAMBIO_DE_EQUIPO
}
