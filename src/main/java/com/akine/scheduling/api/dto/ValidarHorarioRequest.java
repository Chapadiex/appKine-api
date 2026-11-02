package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

@Schema(name = "ValidarHorarioDeTurno", description = "El horario que se quiere reservar y para que.")
public record ValidarHorarioRequest(

		@Schema(example = "42")
		@NotNull(message = "La oferta es obligatoria")
		@Positive(message = "La oferta no es valida") Long ofertaId,
		@Schema(example = "2026-10-30T17:30:00Z") @NotNull(message = "El inicio es obligatorio") Instant inicio,
		@Schema(description = "Omitirlo es \"cualquier profesional\".", example = "31")
		@Positive(message = "El profesional no es valido") Long profesionalId,
		@Schema(example = "55") @Positive(message = "El espacio no es valido") Long espacioId,
		@Schema(example = "128") @Positive(message = "La persona no es valida") Long personaId,
		@Schema(example = "9") @Positive(message = "El caso no es valido") Long casoId,
		@Schema(example = "57") @Positive(message = "El plan no es valido") Long planId,
		@Schema(description = "El turno que se esta moviendo: no choca contra si mismo.", example = "301")
		@Positive(message = "El turno excluido no es valido") Long turnoExcluidoId) {
}
