package com.akine.knowledge.domain.exception;

/** El modelo de lenguaje no responde o se vencio el plazo. */
public class AsistenteNoDisponibleException extends RuntimeException {

	public AsistenteNoDisponibleException() {
		super("El asistente no esta disponible en este momento.");
	}

	public AsistenteNoDisponibleException(Throwable causa) {
		super("El asistente no esta disponible en este momento.", causa);
	}
}
