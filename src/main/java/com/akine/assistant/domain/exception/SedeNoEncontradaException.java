package com.akine.assistant.domain.exception;

/**
 * La sede de la ruta no existe o es de otra organizacion: 404, nunca 403 (un 403 confirmaria que
 * existe).
 */
public class SedeNoEncontradaException extends RuntimeException {

	public SedeNoEncontradaException() {
		super("La sede no existe en esta organizacion.");
	}
}
