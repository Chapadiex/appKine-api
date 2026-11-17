package com.akine.encounter.domain.exception;

/**
 * El modelo de las sugerencias de evolucion no respondio, respondio con error o vencio el plazo.
 * <b>503 {@code modelo-no-disponible}</b>.
 *
 * <p>Es propia de {@code encounter} y no la de {@code platform.spi.modelo}: un advice que mapeara la
 * del {@code spi} atraparia tambien las de los otros modulos que usan el modelo.
 */
public class ModeloDeEvolucionNoDisponibleException extends RuntimeException {

	public ModeloDeEvolucionNoDisponibleException(Throwable causa) {
		super("El modelo de sugerencias no esta disponible", causa);
	}
}
