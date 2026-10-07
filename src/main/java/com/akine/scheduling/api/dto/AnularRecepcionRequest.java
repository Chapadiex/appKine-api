package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Anular un check-in hecho por error. */
@Schema(
		name = "AnularRecepcion",
		description = "Anulacion de un check-in hecho por error. La fila queda como historia.")
public record AnularRecepcionRequest(

		@Schema(description = "Por que se anula. Opcional.", example = "Se marco la llegada en el turno equivocado")
		@Size(max = 300)
		String motivo,

		@Schema(description = "Version que devolvio la ultima lectura de la recepcion.", example = "0")
		@NotNull(message = "expectedVersion es obligatorio")
		@Min(0)
		Long expectedVersion) {
}
