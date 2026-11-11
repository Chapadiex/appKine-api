package com.akine.knowledge.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Evento SSE {@code fuentes}: una vez, antes del texto, solo si hubo material. */
@Schema(description = "Evento `fuentes` del stream de consultarBiblioteca: los pasajes que se le pasaron al "
		+ "modelo, una vez y antes del texto")
public record ConsultaEventoFuentes(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FuenteDeConsultaResponse> fuentes) {
}
