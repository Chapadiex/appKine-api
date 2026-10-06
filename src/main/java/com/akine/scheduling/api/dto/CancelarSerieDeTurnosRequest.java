package com.akine.scheduling.api.dto;

import com.akine.scheduling.domain.AlcanceDeSerie;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Schema(
		name = "CancelarSerieDeTurnos",
		description = "Cancelacion con alcance (DP-04). Solo se cancelan turnos FUTUROS PENDIENTES: "
				+ "los pasados, los ya cancelados o ausentes, los que estan en espera y los que "
				+ "tienen atencion se informan como omitidos y no se tocan.")
public record CancelarSerieDeTurnosRequest(

		@Schema(description = "Sobre que turnos de la serie actua", example = "ESTE_Y_SIGUIENTES")
		@NotNull AlcanceDeSerie alcance,

		@Schema(description = "Turno desde el que se cuenta. Obligatorio salvo en `TODA_LA_SERIE`.", example = "301")
		@Positive Long turnoId,

		@Schema(description = "Por que se cancela. **Obligatorio** (DP-04).", example = "El paciente termino el tratamiento")
		@NotBlank(message = "El motivo de cancelacion es obligatorio")
		@Size(max = 300)
		String motivo,

		@Schema(
				description = "**Confirmacion explicita** (DP-04): la cantidad de turnos afectados que "
						+ "mostro la previsualizacion (`GET .../alcance`). Si ya no coincide, 409 y no "
						+ "se cancela nada.",
				example = "6")
		@NotNull(message = "cantidadConfirmada es obligatoria")
		@Min(1)
		Integer cantidadConfirmada) {
}
