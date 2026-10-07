package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Pasar a espera o llamar: lo unico que viaja es la version leida. La hora la pone el servidor. */
@Schema(
		name = "TransicionDeRecepcion",
		description = "Transicion de la recepcion sin datos propios: solo el control optimista.")
public record TransicionDeRecepcionRequest(

		@Schema(
				description = "Version que devolvio la ultima lectura de la recepcion. Si otro "
						+ "operador la movio entre medio, la transicion se rechaza con 409.",
				example = "1")
		@NotNull(message = "expectedVersion es obligatorio")
		@Min(0)
		Long expectedVersion) {
}
