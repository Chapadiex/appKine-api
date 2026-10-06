package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Schema(
		name = "CrearSerieDeTurnos",
		description = "Alta de una serie semanal de turnos (DP-04). Se reservan TODAS las "
				+ "ocurrencias o ninguna: si una no tiene lugar, no se crea nada y el 409 la nombra "
				+ "en `ocurrenciaInicio`. Termina por `cantidad` o por `fechaHasta`, exactamente uno.")
public record CrearSerieDeTurnosRequest(

		@Schema(description = "Oferta de la sede", example = "42")
		@NotNull @Positive Long ofertaId,

		@Schema(description = "Persona del padron, con perfil de paciente vigente", example = "128")
		@NotNull @Positive Long personaId,

		@Schema(
				description = "Membership del profesional de todas las ocurrencias. Obligatorio si la "
						+ "oferta lo requiere.",
				example = "31")
		@Positive Long profesionalId,

		@ArraySchema(
				arraySchema = @Schema(description = "Dias de la semana, ISO: 1 = lunes ... 7 = domingo"),
				schema = @Schema(example = "1", minimum = "1", maximum = "7"))
		@NotEmpty @Size(max = 7) List<@NotNull @Min(1) @Max(7) Integer> diasSemana,

		@Schema(
				description = "Hora de inicio, en la zona de la sede",
				type = "string", format = "time", example = "09:00:00")
		@NotNull LocalTime hora,

		@Schema(description = "Primer dia en que puede caer una ocurrencia, en la zona de la sede", example = "2026-10-12")
		@NotNull LocalDate fechaDesde,

		@Schema(description = "Ultimo dia en que puede caer una ocurrencia. Excluyente con `cantidad`.", example = "2026-12-21")
		LocalDate fechaHasta,

		@Schema(description = "Cantidad de turnos. Excluyente con `fechaHasta`. Maximo 52.", example = "10")
		@Min(1) @Max(52) Integer cantidad,

		@Schema(
				description = "Clave del cliente para que un reintento no cree una segunda serie. "
						+ "Reusarla con **otro** contenido devuelve 409 `idempotency-key-conflict`.",
				example = "1c9e7b2a-3f4d-4e5f-8a91-2c3d4e5f6a7b")
		@Size(max = 80) String idempotencyKey) {
}
