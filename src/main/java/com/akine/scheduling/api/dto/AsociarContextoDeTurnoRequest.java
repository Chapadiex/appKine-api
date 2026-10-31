package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(name = "AsociarContextoDeTurno", description = "Caso y plan del turno. Omitir los dos lo desasocia.")
public record AsociarContextoDeTurnoRequest(

		@Schema(example = "9")
		@Positive Long casoId,

		@Schema(example = "57")
		@Positive Long planId,

		@Schema(description = "Version que devolvio la ultima lectura del turno.", example = "1")
		@NotNull @Min(0) Long expectedVersion) {
}
