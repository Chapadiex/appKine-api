package com.akine.offering.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Cambia el fin de la vigencia de un precio particular (B-3, RF-M16-009). Es lo unico editable: el
 * importe no se corrige en el lugar, se da de baja y se carga otro.
 */
@Schema(description = "Nuevo fin de vigencia del precio particular")
public record CambiarFinPrecioParticularRequest(

		@Schema(description = "ULTIMO dia en que rige, INCLUSIVE. Null reabre la vigencia sin fin",
				example = "2027-02-28", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta,

		@Schema(description = "Version del precio que se esta editando", example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "expectedVersion es obligatoria")
		Long expectedVersion) {
}
