package com.akine.encounter.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** La ventana para enmendar de la organizacion (DP-43, U-25). */
@Schema(
		name = "FijarPoliticaDeEnmienda",
		description = "Dias desde el cierre en que una sesion se puede enmendar.")
public record FijarPoliticaDeEnmiendaRequest(

		@Schema(description = "Dias desde el cierre, de 1 a 3650.", example = "30")
		@NotNull @Min(1) @Max(3650) Integer ventanaDias,

		@Schema(description = "Version leida. **Nula** si se leyo la politica por defecto "
				+ "(`porDefecto = true`), que todavia no tiene fila. Si no coincide, 409 "
				+ "`concurrent-modification`.", example = "0",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@PositiveOrZero Long version) {
}
