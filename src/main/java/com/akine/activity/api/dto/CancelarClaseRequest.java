package com.akine.activity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Lo que manda quien cancela una clase.
 *
 * <p>El motivo es <b>obligatorio</b> (RN-M28-009): una clase cancelada sin motivo deja al mostrador
 * sin nada que decirle a quien se presenta.
 */
@Schema(
		name = "CancelarClase",
		description = "Cancela la clase. Es idempotente: una segunda ejecucion devuelve la misma "
				+ "clase con el motivo original y no registra un segundo evento.")
public record CancelarClaseRequest(

		@Schema(description = "Por que se cancela", example = "El instructor se reporto enfermo")
		@NotBlank @Size(max = 300) String motivo) {
}
