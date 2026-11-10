package com.akine.knowledge.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Datos editables de un libro. Los largos los valida {@code LibroService} (400). */
@Schema(description = "Datos editables de un libro")
public record LibroDatosRequest(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 300) String titulo,
		@Schema(maxLength = 500) String autores,
		@Schema(maxLength = 100) String edicion,
		@Schema(maxLength = 100) String area,
		@Schema(description = "Version leida, para el control optimista") long version) {
}
