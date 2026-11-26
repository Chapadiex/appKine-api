package com.akine.assistant.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** El token que dio @BotFather al crear el bot. */
@Schema(name = "ConectarBotDeTelegram")
public record ConectarBotDeTelegramRequest(
		@Schema(description = "El token del bot, tal como lo da @BotFather",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "1234567890:AAH...")
		@NotBlank(message = "El token del bot es obligatorio")
		@Size(max = 120, message = "El token del bot admite hasta 120 caracteres")
		String token) {
}
