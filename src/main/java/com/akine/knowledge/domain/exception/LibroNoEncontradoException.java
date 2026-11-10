package com.akine.knowledge.domain.exception;

/**
 * El libro no existe o es de otra organizacion, o la organizacion de la ruta no es la del contexto:
 * 404 siempre. La falta de contexto no es esto: {@code AccessDeniedException}, 403.
 */
public class LibroNoEncontradoException extends RuntimeException {

	public LibroNoEncontradoException() {
		super("El libro no existe.");
	}
}
