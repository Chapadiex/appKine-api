package com.akine.assistant.api.dto;

import com.akine.assistant.application.TelegramService.MiVinculo;
import com.akine.assistant.domain.VinculoDeTelegram;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** La cuenta de quien pregunta con Telegram, y el bot al que tiene que escribirle. */
@Schema(name = "VinculoDeTelegram")
public record VinculoDeTelegramResponse(
		@Schema(description = "Si la cuenta ya esta vinculada a un usuario de Telegram",
				requiredMode = Schema.RequiredMode.REQUIRED)
		boolean vinculado,
		@Schema(description = "El nombre del usuario de Telegram vinculado")
		String telegramNombre,
		Instant vinculadoEn,
		@Schema(description = "La sede en la que se carga lo que se manda por Telegram")
		Long consultorioId,
		@Schema(description = "El codigo pendiente para mandarle al bot como /vincular CODIGO", example = "482913")
		String codigo,
		Instant codigoVenceEn,
		@Schema(description = "El bot del centro, sin @; vacio si el centro no conecto ninguno")
		String botUsuario) {

	public static VinculoDeTelegramResponse de(MiVinculo mio, Instant ahora) {
		VinculoDeTelegram v = mio.vinculo();
		if (v == null) {
			return new VinculoDeTelegramResponse(false, null, null, null, null, null, mio.botUsuario());
		}
		boolean vigente = v.codigoVigente(ahora);
		return new VinculoDeTelegramResponse(v.vinculado(), v.telegramNombre(), v.vinculadoEn(),
				v.vinculado() ? v.consultorioId() : null, vigente ? v.codigo() : null,
				vigente ? v.codigoVenceEn() : null, mio.botUsuario());
	}
}
