package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** La decision explicita de atender como Particular (RF-M13-005). */
@Schema(
		name = "AtenderComoParticular",
		description = "Continuar la recepcion como Particular. No modifica la cobertura maestra del "
				+ "paciente (RN-M13-004).")
public record AtenderComoParticularRequest(

		@Schema(
				description = "Por que se atiende como Particular. **Obligatorio**: es una decision "
						+ "del operador y tiene que quedar explicada.",
				example = "Sin orden medica; el paciente prefiere abonar la sesion")
		@NotBlank(message = "El motivo es obligatorio para atender como Particular")
		@Size(max = 300)
		String motivo,

		@Schema(description = "Version que devolvio la ultima lectura de la recepcion.", example = "1")
		@NotNull(message = "expectedVersion es obligatorio")
		@Min(0)
		Long expectedVersion) {
}
