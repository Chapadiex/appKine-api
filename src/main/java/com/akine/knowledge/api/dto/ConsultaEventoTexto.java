package com.akine.knowledge.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Evento SSE {@code texto}: un fragmento de la respuesta, en orden. */
@Schema(description = "Evento `texto` del stream de consultarBiblioteca: un fragmento de la respuesta; se "
		+ "concatenan en el orden en que llegan")
public record ConsultaEventoTexto(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String texto) {
}
