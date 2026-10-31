package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

@Schema(name = "AsignarDesdeListaDeEspera", description = "El horario que se le da. Pasa por las mismas validaciones que cualquier reserva.")
public record AsignarDesdeListaDeEsperaRequest(
		@Schema(example = "2026-11-03T19:00:00Z") @NotNull Instant inicio,
		@Schema(example = "31") @Positive Long profesionalId,
		@Schema(example = "55") @Positive Long espacioId,
		@Schema(example = "0") @NotNull @Min(0) Long expectedVersion) {
}
