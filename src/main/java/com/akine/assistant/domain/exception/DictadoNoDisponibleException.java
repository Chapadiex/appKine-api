package com.akine.assistant.domain.exception;

/**
 * El transcriptor, o el modelo que reparte el texto en campos, no responde o se vencio el plazo.
 */
public class DictadoNoDisponibleException extends RuntimeException {

	public DictadoNoDisponibleException() {
		super("El dictado no esta disponible en este momento.");
	}

	public DictadoNoDisponibleException(Throwable causa) {
		super("El dictado no esta disponible en este momento.", causa);
	}
}
