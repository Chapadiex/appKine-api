package com.akine.assistant.domain.exception;

/** El centro no tiene un bot de Telegram conectado (409 {@code telegram-sin-bot}). */
public class BotDeTelegramNoConfiguradoException extends RuntimeException {

	public BotDeTelegramNoConfiguradoException() {
		super("El centro no tiene un bot de Telegram conectado: lo conecta el administrador en "
				+ "Organización → Inteligencia artificial.");
	}
}
