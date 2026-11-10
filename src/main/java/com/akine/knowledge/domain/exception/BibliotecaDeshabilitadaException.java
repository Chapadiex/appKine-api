package com.akine.knowledge.domain.exception;

/** La biblioteca esta apagada por configuracion. */
public class BibliotecaDeshabilitadaException extends RuntimeException {

	public BibliotecaDeshabilitadaException() {
		super("La biblioteca esta deshabilitada.");
	}
}
