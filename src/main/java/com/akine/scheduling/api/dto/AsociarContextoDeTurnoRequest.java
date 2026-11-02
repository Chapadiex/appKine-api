package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(name = "AsociarContextoDeTurno", description = "Caso y plan del turno. Omitir los dos lo desasocia.")
public record AsociarContextoDeTurnoRequest(

		@Schema(example = "9")
		@Positive(message = "El caso no es valido") Long casoId,

		@Schema(example = "57")
		@Positive(message = "El plan no es valido") Long planId,

		@Schema(description = "Version que devolvio la ultima lectura del turno.", example = "1")
		@NotNull(message = "La version esperada es obligatoria")
		@Min(value = 0, message = "La version esperada no puede ser negativa") Long expectedVersion) {
}
