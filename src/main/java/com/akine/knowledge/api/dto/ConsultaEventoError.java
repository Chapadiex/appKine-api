package com.akine.knowledge.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Evento SSE {@code error}: la respuesta se corto; el texto parcial que llego queda. */
@Schema(description = "Evento `error` del stream de consultarBiblioteca: la respuesta se corto (modelo caido o "
		+ "limite de 90 s) y el stream se cierra; el texto que ya llego queda")
public record ConsultaEventoError(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uri",
				description = "problemType, hoy siempre .../asistente-no-disponible") String type) {
}
