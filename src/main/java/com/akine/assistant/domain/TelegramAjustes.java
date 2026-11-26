package com.akine.assistant.domain;

/**
 * Ajustes de la carga por Telegram (docs/diseno/AKINE-telegram.md), ya validados.
 *
 * @param enabled       el interruptor {@code akine.telegram.enabled}; apagado, la API responde 409
 *                      {@code telegram-deshabilitado} y no se sondea ningun bot
 * @param urlApp        la direccion publica del frontend, para los enlaces de las respuestas
 * @param maxBytesAudio el tope de una nota de voz; mas grande no se descarga
 */
public record TelegramAjustes(boolean enabled, String urlApp, long maxBytesAudio) {

	public TelegramAjustes {
		urlApp = urlApp == null ? "" : urlApp.replaceAll("/+$", "");
		if (maxBytesAudio <= 0) {
			throw new IllegalArgumentException("El tope del audio tiene que ser positivo");
		}
	}
}
