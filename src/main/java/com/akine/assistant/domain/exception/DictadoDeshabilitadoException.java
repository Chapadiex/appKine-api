package com.akine.assistant.domain.exception;

/** El dictado por voz esta apagado por configuracion ({@code akine.dictado.enabled}). */
public class DictadoDeshabilitadoException extends RuntimeException {

	public DictadoDeshabilitadoException() {
		super("El dictado por voz no esta habilitado en este centro.");
	}
}
