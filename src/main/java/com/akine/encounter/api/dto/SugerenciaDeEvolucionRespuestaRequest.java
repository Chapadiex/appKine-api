package com.akine.encounter.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Aceptar o descartar una sugerencia de evolucion. */
@Schema(description = "Respuesta del profesional a una sugerencia de evolucion")
public record SugerenciaDeEvolucionRespuestaRequest(

		@Schema(description = "ACEPTADA o DESCARTADA", allowableValues = {"ACEPTADA", "DESCARTADA"})
		@NotNull(message = "La respuesta es obligatoria")
		@Pattern(regexp = "ACEPTADA|DESCARTADA", message = "La respuesta debe ser ACEPTADA o DESCARTADA")
		String respuesta,

		@Schema(description = "Obligatorio al descartar, hasta 300 caracteres",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 300, message = "El motivo admite como maximo 300 caracteres")
		String motivo,

		@Schema(description = "Version leida. Si no coincide, 409 `concurrent-modification`", example = "0")
		@NotNull(message = "La version es obligatoria")
		@PositiveOrZero(message = "La version no puede ser negativa")
		Long version) {
}
