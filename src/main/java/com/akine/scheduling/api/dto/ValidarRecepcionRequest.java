package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Pedido de validacion administrativa. El resultado lo calcula el servidor. */
@Schema(
		name = "ValidarRecepcion",
		description = "Validacion de cobertura y documentacion (RF-M13-003/004). El servidor decide "
				+ "si queda VALIDADA u OBSERVADA: no se manda el resultado, solo la cobertura "
				+ "elegida, si la hay.")
public record ValidarRecepcionRequest(

		@Schema(
				description = "Cobertura del paciente con la que se quiere atender. Si se omite, se "
						+ "usa la primera que aplique a la practica (la principal va primera).",
				example = "412")
		@Positive
		Long coberturaId,

		@Schema(description = "Version que devolvio la ultima lectura de la recepcion.", example = "0")
		@NotNull(message = "expectedVersion es obligatorio")
		@Min(0)
		Long expectedVersion) {
}
