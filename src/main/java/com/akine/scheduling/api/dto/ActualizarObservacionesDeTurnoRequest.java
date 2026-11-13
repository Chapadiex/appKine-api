package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Lo que manda quien cambia las observaciones de un turno. */
@Schema(
		name = "ActualizarObservacionesDeTurno",
		description = "Observaciones administrativas del turno. Vacias o ausentes las borran. **Nunca "
				+ "informacion clinica**: eso es de la Sesion y de la historia clinica.")
public record ActualizarObservacionesDeTurnoRequest(

		@Schema(description = "Texto nuevo. Vacio o ausente borra las observaciones.", example = "Trae los estudios")
		@Size(max = 500, message = "Las observaciones no pueden superar los 500 caracteres")
		String observaciones,

		@Schema(description = "Version que devolvio la ultima lectura del turno.", example = "0")
		@NotNull(message = "La version esperada es obligatoria")
		@Min(value = 0, message = "La version esperada no puede ser negativa")
		Long expectedVersion) {
}
