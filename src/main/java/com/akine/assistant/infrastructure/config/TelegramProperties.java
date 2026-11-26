package com.akine.assistant.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code akine.telegram}: carga por el bot de Telegram de cada centro (docs/diseno/AKINE-telegram.md).
 *
 * @param enabled          apagado por defecto: la API responde 409 y no se sondea nada
 * @param apiUrl           la Bot API; se cambia solo para un servidor propio de Bot API o un test
 * @param intervaloMs      cada cuanto se pregunta a Telegram por mensajes nuevos de cada bot
 * @param claveCifrado     clave del despliegue para cifrar los tokens (cualquier texto largo). Vacia,
 *                         se deriva del secreto del JWT: cambiar ese secreto deja los bots sin token
 * @param urlApp           la direccion del frontend, para los enlaces que manda el bot
 * @param maxBytesAudio    tope de una nota de voz
 * @param timeoutSegundos  plazo de cada llamada a Telegram
 */
@ConfigurationProperties(prefix = "akine.telegram")
public record TelegramProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("https://api.telegram.org") String apiUrl,
		@DefaultValue("3000") long intervaloMs,
		@DefaultValue("") String claveCifrado,
		@DefaultValue("http://localhost:4200") String urlApp,
		@DefaultValue("10485760") long maxBytesAudio,
		@DefaultValue("20") int timeoutSegundos) {
}
