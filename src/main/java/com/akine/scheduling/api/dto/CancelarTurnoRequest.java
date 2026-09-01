package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Lo que manda quien cancela un turno.
 *
 * <p>El motivo es obligatorio y no es una formalidad: DP-04 lo exige, y un turno que desaparece de
 * la agenda sin explicacion es exactamente lo que despues nadie puede reconstruir.
 */
@Schema(
		name = "CancelarTurno",
		description = "Cancelacion de un turno futuro. Libera el lugar sin borrar la fila.")
public record CancelarTurnoRequest(

		@Schema(
				description = "Por que se cancela. **Obligatorio** (DP-04).",
				example = "El paciente aviso que no puede venir")
		@NotBlank(message = "El motivo de cancelacion es obligatorio")
		@Size(max = 300)
		String motivo,

		@Schema(
				description = "Version que devolvio la ultima lectura del turno. Si otro operador "
						+ "lo modifico entre medio, la cancelacion se rechaza con 409 en vez de "
						+ "pisarlo.",
				example = "0")
		@NotNull(message = "expectedVersion es obligatorio")
		@Min(0)
		Long expectedVersion) {
}
