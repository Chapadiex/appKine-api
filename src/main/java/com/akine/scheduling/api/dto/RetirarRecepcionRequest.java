package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** La persona se retiro sin ser atendida (U-21, DP-43). */
@Schema(
		name = "RetirarRecepcion",
		description = "La persona se retiro sin ser atendida. La llegada consta y el turno no cambia.")
public record RetirarRecepcionRequest(

		@Schema(
				description = "Por que se retiro. **Obligatorio**: es lo unico que explica despues por "
						+ "que un turno con llegada quedo sin atender.",
				example = "No quiso esperar la demora de 40 minutos")
		@NotBlank(message = "El motivo es obligatorio para registrar que la persona se retiro")
		@Size(max = 300)
		String motivo,

		@Schema(description = "Version que devolvio la ultima lectura de la recepcion.", example = "2")
		@NotNull(message = "expectedVersion es obligatorio")
		@Min(0)
		Long expectedVersion) {
}
