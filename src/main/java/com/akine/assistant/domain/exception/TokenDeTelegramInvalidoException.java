package com.akine.assistant.domain.exception;

/** Telegram no reconoce el token, o no se pudo validar (400 {@code validation-error}). */
public class TokenDeTelegramInvalidoException extends RuntimeException {

	public TokenDeTelegramInvalidoException(String motivo) {
		super(motivo);
	}
}
