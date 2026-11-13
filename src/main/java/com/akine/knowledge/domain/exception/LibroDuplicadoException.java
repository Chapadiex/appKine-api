package com.akine.knowledge.domain.exception;

/** Ya existe un libro con el mismo contenido en la organizacion. */
public class LibroDuplicadoException extends RuntimeException {

	private final long libroId;

	public LibroDuplicadoException(long libroId) {
		super("Ya existe un libro con el mismo contenido en la biblioteca.");
		this.libroId = libroId;
	}

	public long getLibroId() {
		return libroId;
	}
}
