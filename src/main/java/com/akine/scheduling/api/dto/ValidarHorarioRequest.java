package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

@Schema(name = "ValidarHorarioDeTurno", description = "El horario que se quiere reservar y para que.")
public record ValidarHorarioRequest(

		@Schema(example = "42") @NotNull @Positive Long ofertaId,
		@Schema(example = "2026-10-30T17:30:00Z") @NotNull Instant inicio,
		@Schema(description = "Omitirlo es \"cualquier profesional\".", example = "31") @Positive Long profesionalId,
		@Schema(example = "55") @Positive Long espacioId,
		@Schema(example = "128") @Positive Long personaId,
		@Schema(example = "9") @Positive Long casoId,
		@Schema(example = "57") @Positive Long planId,
		@Schema(description = "El turno que se esta moviendo: no choca contra si mismo.", example = "301")
		@Positive Long turnoExcluidoId) {
}
