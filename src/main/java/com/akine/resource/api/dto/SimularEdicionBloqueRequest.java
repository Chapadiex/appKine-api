package com.akine.resource.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * La edicion que se quiere evaluar antes de aplicarla (A-11). Los mismos campos que
 * {@link UpdateBloqueRequest} con la misma semantica de PATCH, <b>sin {@code version}</b>: la
 * consulta previa no muta nada, asi que no tiene contra que comparar. La version se sigue
 * exigiendo en el {@code PUT}.
 */
@Schema(description = "Edicion propuesta de un bloque de disponibilidad, para evaluar su impacto "
		+ "antes de aplicarla. Mismos campos y semantica que la edicion, sin version")
public record SimularEdicionBloqueRequest(

		@Schema(description = "Nuevo dia de la semana, ISO-8601: lunes = 1 .. domingo = 7. "
				+ "Omitirlo lo deja como estaba", example = "3",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Min(value = 1, message = "El dia de la semana va de 1 (lunes) a 7 (domingo)")
		@Max(value = 7, message = "El dia de la semana va de 1 (lunes) a 7 (domingo)")
		Integer diaSemana,

		@Schema(description = "Nueva hora local de inicio. Omitirla la deja como estaba",
				type = "string", example = "10:00", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@JsonDeserialize(using = HoraDelDia.Deserializador.class)
		LocalTime horaDesde,

		@Schema(description = "Nueva hora local de fin, EXCLUSIVA. 24:00 para un bloque que llega "
				+ "a la medianoche. Omitirla la deja como estaba",
				type = "string", example = "13:00", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@JsonDeserialize(using = HoraDelDia.Deserializador.class)
		LocalTime horaHasta,

		@Schema(description = "Nuevo primer dia de vigencia. Omitirlo lo deja como estaba",
				example = "2026-09-01", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaDesde,

		@Schema(description = "Nuevo fin de vigencia, EXCLUSIVO. Se IGNORA si "
				+ "limpiarVigenciaHasta viene en true", example = "2027-01-01",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta,

		@Schema(description = "Saca el fin de vigencia y deja el bloque sin fin previsto",
				example = "false", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean limpiarVigenciaHasta) {
}
