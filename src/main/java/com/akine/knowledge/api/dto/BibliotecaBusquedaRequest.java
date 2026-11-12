package com.akine.knowledge.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Consulta a la biblioteca. Los limites los valida {@code BusquedaService} (400). */
@Schema(description = "Consulta a la biblioteca")
public record BibliotecaBusquedaRequest(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 600) String consulta,
		@Schema(description = "Pasajes a devolver, de 1 a 20; por defecto 5", minimum = "1",
				maximum = "20") Integer k) {
}
