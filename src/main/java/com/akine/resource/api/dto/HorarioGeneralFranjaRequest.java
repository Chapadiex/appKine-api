package com.akine.resource.api.dto;

import com.akine.resource.domain.FranjaHorarioGeneral.Franja;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.LocalTime;

/**
 * Una franja semanal del horario general de la sede, en la edicion del calendario
 * (RF-M03-003, A-8). Misma convencion que un bloque de disponibilidad: horas locales de la sede
 * y la medianoche como fin escrita {@code 24:00}.
 */
@Schema(description = "Franja semanal del horario general de la sede")
public record HorarioGeneralFranjaRequest(

		@Schema(description = "Dia de la semana ISO-8601: 1 = lunes .. 7 = domingo",
				example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El dia de la semana es obligatorio")
		@Min(value = 1, message = "El dia de la semana va de 1 (lunes) a 7 (domingo)")
		@Max(value = 7, message = "El dia de la semana va de 1 (lunes) a 7 (domingo)")
		Integer diaSemana,

		@Schema(description = "Hora local de apertura", type = "string", example = "09:00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La hora de apertura es obligatoria")
		@JsonDeserialize(using = HoraDelDia.Deserializador.class)
		LocalTime horaDesde,

		@Schema(description = "Hora local de cierre, EXCLUSIVA. La medianoche es 24:00",
				type = "string", example = "13:00", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La hora de cierre es obligatoria")
		@JsonDeserialize(using = HoraDelDia.Deserializador.class)
		LocalTime horaHasta) {

	public Franja aFranja() {
		return new Franja(diaSemana, horaDesde, horaHasta);
	}
}
