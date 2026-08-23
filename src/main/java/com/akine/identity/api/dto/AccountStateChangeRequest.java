package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motivo de una transicion administrativa de cuenta (RF-M02-005).
 *
 * <p>El motivo es obligatorio en las tres operaciones, incluido el desbloqueo. No es
 * burocracia: quitarle o devolverle el acceso a una persona es lo que despues se revisa en una
 * auditoria, y "por que" es la pregunta que se hace ahi. Un motivo vacio la deja sin respuesta.
 */
@Schema(description = "Motivo auditable de la transicion de estado de la cuenta")
public record AccountStateChangeRequest(

		@Schema(
				description = "Por que se ejecuta la transicion. Queda en la auditoria",
				example = "Baja del profesional por fin de contrato",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo es obligatorio")
		@Size(max = 500, message = "El motivo no puede superar los 500 caracteres")
		String reason) {
}
