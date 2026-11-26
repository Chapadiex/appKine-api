package com.akine.assistant.domain;

import java.time.Instant;

/**
 * El bot de Telegram de un centro.
 *
 * @param token           el token en claro; {@code null} si el bot esta desconectado. Nunca sale por
 *                        la API ni va al log
 * @param ultimoUpdateId  el ultimo update procesado: el sondeo pide desde el siguiente
 * @param ultimoError     el ultimo error al hablar con Telegram, o {@code null}
 * @param ultimoContactoEn la ultima vez que Telegram respondio, o {@code null}
 */
public record BotDeTelegram(
		long organizationId,
		String token,
		long botId,
		String usuario,
		boolean activo,
		long ultimoUpdateId,
		String ultimoError,
		Instant ultimoContactoEn) {
}
