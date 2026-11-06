package com.akine.resource.api.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Alta de VARIOS bloques recurrentes de un profesional, con el mismo horario y la misma vigencia,
 * en una sola operacion atomica: "de lunes a viernes de 09 a 13" (RF-M05-003).
 *
 * <p>Es {@link CreateBloqueRequest} con un conjunto de dias en vez de uno. Todo lo demas —las
 * horas de pared, la medianoche como {@code 24:00}, la vigencia opcional— significa exactamente lo
 * mismo, y se valida igual, dia por dia.
 */
@Schema(description = "Datos para dar de alta varios bloques recurrentes de disponibilidad, uno por "
		+ "dia, con el mismo horario y la misma vigencia, en una sola transaccion")
public record CreateBloquesRequest(

		@ArraySchema(
				schema = @Schema(
						description = "Dia de la semana en formato ISO-8601: lunes = 1 .. domingo = 7",
						minimum = "1",
						maximum = "7"),
				arraySchema = @Schema(
						description = "Dias de la semana a cargar. Al menos uno; los repetidos se cargan una vez",
						example = "[1, 2, 3, 4, 5]",
						requiredMode = Schema.RequiredMode.REQUIRED),
				minItems = 1,
				maxItems = 7)
		@NotEmpty(message = "Hay que elegir al menos un dia de la semana")
		@Size(max = 7, message = "Una semana tiene siete dias")
		List<@NotNull(message = "El dia de la semana es obligatorio")
			@Min(value = 1, message = "El dia de la semana va de 1 (lunes) a 7 (domingo)")
			@Max(value = 7, message = "El dia de la semana va de 1 (lunes) a 7 (domingo)") Integer> diasSemana,

		@Schema(
				description = "Hora local de inicio, en la zona de la sede, compartida por todos los dias",
				type = "string",
				example = "09:00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La hora de inicio es obligatoria")
		@JsonDeserialize(using = HoraDelDia.Deserializador.class)
		LocalTime horaDesde,

		@Schema(
				description = "Hora local de fin, EXCLUSIVA, compartida por todos los dias. La medianoche "
						+ "se manda como 24:00",
				type = "string",
				example = "13:00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La hora de fin es obligatoria")
		@JsonDeserialize(using = HoraDelDia.Deserializador.class)
		LocalTime horaHasta,

		@Schema(
				description = "Primer dia en que los bloques rigen. Omitirlo significa HOY en la zona "
						+ "de la sede",
				example = "2026-09-01",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaDesde,

		@Schema(
				description = "Primer dia en que los bloques ya NO rigen, EXCLUSIVO. Omitirlo "
						+ "significa sin fin previsto",
				example = "2027-01-01",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta) {
}
