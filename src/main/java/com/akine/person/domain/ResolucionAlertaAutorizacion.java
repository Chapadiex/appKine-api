package com.akine.person.domain;

/**
 * Como se resolvio una alerta sobre una autorizacion.
 *
 * <p>Hoy un solo camino: revertir el consumo (RF-M17-005). Descartar la alerta sin revertir no
 * tiene RF que lo pida y queda como decision del usuario (docs/diseno/AKINE-C-4-consumo.md §9).
 */
public enum ResolucionAlertaAutorizacion {

	/** Alguien revirtio el consumo que la alerta senalaba. */
	REVERTIDO
}
