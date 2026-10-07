package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Una franja semanal del horario general de la sede (RF-M03-002, A-8).
 *
 * <p>Las horas viajan como texto {@code HH:mm} en la zona de la sede, y la medianoche como fin
 * se escribe {@code 24:00}: es la misma convencion que la disponibilidad profesional y que la
 * lectura del calendario de la sede. Que el fin sea posterior al inicio y que dos franjas del
 * mismo dia no se pisen se valida al crear, y si falla no queda nada: ni la sede.
 */
@Schema(description = "Franja semanal del horario general de la sede")
public record AltaSedeFranjaHorariaRequest(

		@Schema(description = "Dia de la semana ISO-8601: 1 = lunes .. 7 = domingo",
				example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El dia de la semana es obligatorio")
		@Min(value = 1, message = "El dia de la semana va de 1 (lunes) a 7 (domingo)")
		@Max(value = 7, message = "El dia de la semana va de 1 (lunes) a 7 (domingo)")
		Integer diaSemana,

		@Schema(description = "Hora local de apertura, HH:mm", type = "string", example = "09:00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La hora de apertura es obligatoria")
		@Pattern(regexp = HORA, message = "La hora de apertura tiene que ser HH:mm")
		String horaDesde,

		@Schema(description = "Hora local de cierre, EXCLUSIVA, HH:mm. La medianoche es 24:00. "
				+ "No cruza al dia siguiente", type = "string", example = "13:00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La hora de cierre es obligatoria")
		@Pattern(regexp = HORA, message = "La hora de cierre tiene que ser HH:mm, o 24:00")
		String horaHasta) {

	/** {@code 00:00}..{@code 23:59}, segundos opcionales, o la medianoche como fin. */
	static final String HORA = "^(([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?|24:00(:00)?)$";
}
