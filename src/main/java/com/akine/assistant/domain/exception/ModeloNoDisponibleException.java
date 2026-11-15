package com.akine.assistant.domain.exception;

/** El modelo que interpreta las preguntas no responde o se vencio el plazo. */
public class ModeloNoDisponibleException extends RuntimeException {

	public ModeloNoDisponibleException() {
		super("El asistente no esta disponible en este momento.");
	}

	public ModeloNoDisponibleException(Throwable causa) {
		super("El asistente no esta disponible en este momento.", causa);
	}
}
