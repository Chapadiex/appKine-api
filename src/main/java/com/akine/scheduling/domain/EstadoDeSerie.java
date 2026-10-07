package com.akine.scheduling.domain;

/**
 * Estado DERIVADO de una serie de turnos, para la bandeja (AKINE E-8).
 *
 * <p>La serie no tiene estado persistido y no lo va a tener (E-3, ADR-0011): "la serie esta
 * cancelada" es un hecho de sus turnos, no una columna que pueda contradecirlos. Este enum es la
 * lectura de esos turnos en un instante, calculada al leer y nunca guardada.
 */
public enum EstadoDeSerie {

	/** Le queda al menos un turno pendiente: RESERVADO o CONFIRMADO, vivo y que todavia no empezo. */
	VIGENTE,

	/** No le queda ningun turno pendiente: todos pasaron, se cancelaron o se marcaron ausentes. */
	FINALIZADA
}
