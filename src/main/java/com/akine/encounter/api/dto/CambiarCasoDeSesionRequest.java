package com.akine.encounter.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * El caso que tiene que quedar en la sesion abierta, mas la version que el cliente leyo.
 *
 * @param casoId  caso activo de la misma historia clinica, o {@code null} para quitarlo
 * @param version version de la sesion que el cliente leyo
 */
@Schema(
		name = "CambiarCasoDeSesion",
		description = "Caso Clinico de una atencion abierta. `casoId` nulo quita el caso.")
public record CambiarCasoDeSesionRequest(

		@Schema(description = "Caso activo de la misma historia clinica. **Nulo quita el caso**: "
				+ "la atencion sin caso es legitima (RF-M14-002)", example = "12",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Positive Long casoId,

		@Schema(description = "Version de la sesion que el cliente leyo. Un 409 "
				+ "`concurrent-modification` significa recargar.", example = "3")
		@NotNull @PositiveOrZero Long version) {
}
