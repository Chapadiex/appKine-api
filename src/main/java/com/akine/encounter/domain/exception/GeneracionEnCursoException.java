package com.akine.encounter.domain.exception;

/**
 * La misma cuenta ya tiene un pedido de sugerencias de evolucion corriendo en esta instancia.
 * <b>429 {@code generacion-en-curso}</b>.
 */
public class GeneracionEnCursoException extends RuntimeException {

	public GeneracionEnCursoException() {
		super("Ya hay un pedido de sugerencias en curso para esta cuenta");
	}
}
