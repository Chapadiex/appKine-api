package com.akine.assistant.api.dto;

import com.akine.assistant.domain.BotDeTelegram;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** El bot de Telegram del centro. Nunca trae el token. */
@Schema(name = "BotDeTelegram")
public record BotDeTelegramResponse(
		@Schema(description = "Si hay un bot conectado", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean conectado,
		@Schema(description = "El usuario del bot, sin @", example = "kine_centro_bot")
		String usuario,
		@Schema(description = "El ultimo error al hablar con Telegram; vacio si anda bien")
		String ultimoError,
		@Schema(description = "La ultima vez que Telegram respondio al sondeo")
		Instant ultimoContactoEn) {

	public static BotDeTelegramResponse de(BotDeTelegram bot) {
		return bot == null ? new BotDeTelegramResponse(false, null, null, null)
				: new BotDeTelegramResponse(true, bot.usuario(), bot.ultimoError(), bot.ultimoContactoEn());
	}
}
