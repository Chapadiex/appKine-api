package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

@Schema(description = "Que funciones de IA quiere usar el centro. Reemplazo completo, con la version leida")
public record UpdateFuncionesIaRequest(

		@Schema(description = "Asistente de datos", example = "true")
		@NotNull(message = "Indica si el centro usa el asistente") Boolean asistente,

		@Schema(description = "Alertas e indicadores de evolucion del caso", example = "true")
		@NotNull(message = "Indica si el centro usa la evolucion del caso") Boolean evolucion,

		@Schema(description = "Sugerencias de revision con citas de la biblioteca", example = "true")
		@NotNull(message = "Indica si el centro usa las sugerencias") Boolean sugerencias,

		@Schema(description = "Biblioteca del centro y su consulta", example = "true")
		@NotNull(message = "Indica si el centro usa la biblioteca") Boolean biblioteca,

		@Schema(description = "Version leida", example = "0")
		@NotNull(message = "La version es obligatoria")
		@PositiveOrZero(message = "La version no puede ser negativa") Long version) {
}
