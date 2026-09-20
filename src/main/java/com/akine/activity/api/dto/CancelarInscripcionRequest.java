package com.akine.activity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Lo que manda quien da de baja una inscripcion.
 *
 * <p>El motivo es <b>obligatorio</b>: la fila no se borra —RF-M28-003 prohibe el borrado fisico— y
 * lo que queda tiene que explicar por que se libero el lugar.
 */
@Schema(
		name = "CancelarInscripcion",
		description = "Libera el cupo y conserva el historial individual. Es idempotente: una "
				+ "segunda ejecucion no libera un segundo lugar ni promueve a nadie.")
public record CancelarInscripcionRequest(

		@Schema(description = "Por que se da de baja", example = "La paciente aviso que no viene")
		@NotBlank @Size(max = 300) String motivo) {
}
