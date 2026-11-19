package com.akine.assistant.domain.exception;

/** La accion no existe o es de otra cuenta, sede u organizacion: 404, nunca 403. */
public class AccionNoEncontradaException extends RuntimeException {

	public AccionNoEncontradaException() {
		super("La accion no existe.");
	}
}
