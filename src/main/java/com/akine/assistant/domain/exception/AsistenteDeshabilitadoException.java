package com.akine.assistant.domain.exception;

/** El asistente de datos esta apagado ({@code akine.asistente.enabled}): 409. */
public class AsistenteDeshabilitadoException extends RuntimeException {

	public AsistenteDeshabilitadoException() {
		super("El asistente de datos esta apagado: lo apago el centro (Organizacion, Inteligencia artificial) o no esta disponible en este servidor.");
	}
}
