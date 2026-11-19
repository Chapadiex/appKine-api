package com.akine.assistant.domain.exception;

/** La tarjeta confirmada no es la de la propuesta (otro tipo u otra persona): 400. */
public class AccionInvalidaException extends RuntimeException {

	public AccionInvalidaException() {
		super("La confirmacion no corresponde a lo que se propuso.");
	}
}
