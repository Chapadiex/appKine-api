package com.akine.knowledge.domain.exception;

/** El libro no existe, es de otra organizacion o no hay contexto: 404 siempre. */
public class LibroNoEncontradoException extends RuntimeException {

	public LibroNoEncontradoException() {
		super("El libro no existe.");
	}
}
