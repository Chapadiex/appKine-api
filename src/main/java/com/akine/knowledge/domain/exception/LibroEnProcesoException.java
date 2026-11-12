package com.akine.knowledge.domain.exception;

/** El libro se esta procesando y no admite la operacion pedida. */
public class LibroEnProcesoException extends RuntimeException {

	public LibroEnProcesoException() {
		super("El libro se esta procesando; reintentar cuando termine.");
	}
}
