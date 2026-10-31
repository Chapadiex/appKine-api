package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Schema(
		name = "BuscarDisponibilidadDeTurnos",
		description = "Criterios de la busqueda. Solo la oferta es obligatoria. Sin profesional busca "
				+ "entre todos los habilitados. Sin `hasta` sigue buscando hasta llenar `limite` o "
				+ "agotar 62 dias: con `limite = 1` es el primer turno disponible.")
public record BuscarDisponibilidadRequest(

		@Schema(example = "42") @NotNull @Positive Long ofertaId,

		@Schema(description = "Membership del profesional. Omitirlo es \"cualquier profesional\".", example = "31")
		@Positive Long profesionalId,

		@Schema(description = "Primer dia, en la zona de la sede. Por defecto, hoy.", example = "2026-10-30")
		LocalDate desde,

		@Schema(description = "Ultimo dia, EXCLUSIVO. Maximo 62 dias desde `desde`.", example = "2026-11-30")
		LocalDate hasta,

		@Schema(description = "Franja horaria local: no antes de esta hora", type = "string", format = "time", example = "14:00:00")
		LocalTime horaDesde,

		@Schema(description = "Franja horaria local: empezar antes de esta hora", type = "string", format = "time", example = "18:00:00")
		LocalTime horaHasta,

		@ArraySchema(
				arraySchema = @Schema(description = "Dias de la semana que sirven, ISO: 1 = lunes ... 7 = domingo. Omitirlo es cualquiera."),
				schema = @Schema(example = "1", minimum = "1", maximum = "7"))
		@Size(max = 7) List<@NotNull @Min(1) @Max(7) Integer> dias,

		@Schema(description = "Cuantos horarios devolver. Por defecto 20, maximo 200.", example = "20")
		@Min(1) @Max(200) Integer limite) {
}
