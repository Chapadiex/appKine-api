package com.akine.assistant.domain.exception;

/** El asistente de datos esta apagado ({@code akine.asistente.enabled}): 409. */
public class AsistenteDeshabilitadoException extends RuntimeException {

	public AsistenteDeshabilitadoException() {
		super("El asistente de datos esta deshabilitado en este despliegue.");
	}
}
