package com.akine.encounter.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Quien continua una atencion abierta, por que, y la version que se leyo (DP-43, U-24).
 */
@Schema(
		name = "ReemplazarProfesionalDeSesion",
		description = "Reemplazo autorizado del profesional de una atencion abierta.")
public record ReemplazarProfesionalDeSesionRequest(

		@Schema(description = "Membership del profesional que continua la atencion. Vigente en la "
				+ "sede, con `sesion:register` y habilitado para la oferta.", example = "31")
		@NotNull @Positive Long profesionalId,

		@Schema(description = "Por que se reemplaza. Obligatorio, hasta 500 caracteres. Queda en el "
				+ "registro del reemplazo, no en la auditoria.",
				example = "La profesional se retiro por una urgencia familiar")
		@NotBlank @Size(max = 500) String motivo,

		@Schema(description = "Version de la sesion que el cliente leyo. Un 409 "
				+ "`concurrent-modification` significa recargar.", example = "3")
		@NotNull @PositiveOrZero Long version) {
}
