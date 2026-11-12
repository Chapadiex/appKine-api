package com.akine.knowledge.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Evento SSE {@code fin}: la respuesta termino. */
@Schema(description = "Evento `fin` del stream de consultarBiblioteca: la respuesta termino y el stream se cierra")
public record ConsultaEventoFin(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "true si no hubo material: el texto es el aviso fijo y el modelo no se llamo")
		boolean sinMaterial,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"VECTOR", "PALABRAS"},
				description = "Como se busco: por similitud o por palabras") String camino) {
}
