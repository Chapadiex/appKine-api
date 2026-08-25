package com.akine.resource.domain;

/**
 * Ciclo de una solicitud de alta de catalogo global (RF-M06-005).
 *
 * <p>Tres estados y dos transiciones, las dos terminales:
 * {@code PENDIENTE -> APROBADA} y {@code PENDIENTE -> RECHAZADA}. No hay vuelta atras: si la
 * plataforma se equivoco al rechazar, el centro vuelve a pedir —y esa segunda solicitud es una
 * fila nueva, con su propia justificacion y su propia fecha—. Reabrir una resuelta borraria de
 * que se decidio la primera vez.
 */
public enum SolicitudEstado {

	/** Esperando decision de la plataforma. Es el unico estado que bloquea un duplicado. */
	PENDIENTE,

	/** La plataforma incorporo el concepto al catalogo global. */
	APROBADA,

	/** La plataforma decidio no incorporarlo, con motivo declarado. */
	RECHAZADA
}
