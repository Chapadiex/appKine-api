package com.akine.assistant.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Lo que se entendio del audio. Vacio si no se entendio nada. */
@Schema(name = "TranscripcionDeDictado")
public record TranscripcionResponse(
		@Schema(description = "El texto dictado, tal cual. Vacio si no se entendio nada",
				requiredMode = Schema.RequiredMode.REQUIRED, example = "Refiere menos dolor al subir escaleras.")
		String texto) {
}
