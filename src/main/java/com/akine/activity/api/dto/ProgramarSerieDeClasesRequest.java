package com.akine.activity.api.dto;

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
import java.util.Set;

@Schema(
		name = "ProgramarSerieDeClases",
		description = "Programa una clase en cada fecha del rango que cae en uno de los dias de la "
				+ "semana pedidos, con el mismo horario, profesional, box y cupo. Cada fecha se valida "
				+ "como una clase suelta y la que no se puede programar se informa, sin frenar las demas.")
public record ProgramarSerieDeClasesRequest(

		@Schema(description = "Primer dia del rango, inclusive, en la zona de la sede", example = "2026-11-10",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La fecha desde es obligatoria") LocalDate desde,

		@Schema(description = "Ultimo dia del rango, INCLUSIVE. El rango no puede superar un anio",
				example = "2026-12-31", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La fecha hasta es obligatoria") LocalDate hasta,

		@ArraySchema(
				schema = @Schema(
						description = "Dia de la semana en formato ISO-8601: lunes = 1 .. domingo = 7",
						minimum = "1",
						maximum = "7"),
				arraySchema = @Schema(
						description = "Dias de la semana en que se dicta la clase. Al menos uno",
						example = "[2, 4]",
						requiredMode = Schema.RequiredMode.REQUIRED),
				minItems = 1,
				maxItems = 7,
				uniqueItems = true)
		@NotEmpty(message = "Hay que elegir al menos un dia de la semana")
		@Size(max = 7, message = "Una semana tiene siete dias")
		Set<@NotNull(message = "El dia de la semana es obligatorio")
			@Min(value = 1, message = "El dia de la semana va de 1 (lunes) a 7 (domingo)")
			@Max(value = 7, message = "El dia de la semana va de 1 (lunes) a 7 (domingo)") Integer> diasSemana,

		@Schema(description = "Hora local de inicio, en la zona de la sede", type = "string",
				pattern = "^([01]\\d|2[0-3]):[0-5]\\d$", example = "18:00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La hora de inicio es obligatoria") LocalTime horaInicio,

		@Schema(description = "Hora local de fin, EXCLUSIVA y posterior a la de inicio", type = "string",
				pattern = "^([01]\\d|2[0-3]):[0-5]\\d$", example = "19:00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La hora de fin es obligatoria") LocalTime horaFin,

		@Schema(description = "Membership del profesional a cargo. Obligatorio si la oferta lo requiere",
				example = "31")
		@Positive(message = "El profesional no es valido") Long profesionalId,

		@Schema(description = "Cupo de cada clase. Limitado ademas por la oferta y el box. Minimo 2",
				example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La capacidad es obligatoria")
		@Min(value = 2, message = "La capacidad no puede ser menor que 2") Integer capacidad,

		@Schema(description = "Rotulo de cada clase de la serie", example = "Yoga - martes y jueves")
		@Size(max = 120, message = "El titulo no puede superar los 120 caracteres") String titulo,

		@Schema(
				description = "Clave del cliente para la serie entera. Un reintento con la misma clave "
						+ "devuelve las clases ya creadas en vez de duplicarlas, y sigue con las que faltan",
				example = "b3f1c2a4-5d6e-4f70-8a91-2c3d4e5f6a7b")
		@Size(max = 64, message = "La clave de idempotencia no puede superar los 64 caracteres") String idempotencyKey,

		@Schema(description = "Box elegido. Sin el, el servidor elige en cada fecha el primer box apto y "
				+ "libre (solo si la oferta exige espacio)", example = "8")
		@Positive(message = "El espacio no es valido") Long espacioId) {
}
