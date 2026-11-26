package com.akine.assistant.domain.exception;

/** La carga por Telegram esta apagada por configuracion ({@code akine.telegram.enabled}). */
public class TelegramDeshabilitadoException extends RuntimeException {

	public TelegramDeshabilitadoException() {
		super("La carga por Telegram no esta habilitada en este despliegue.");
	}
}
