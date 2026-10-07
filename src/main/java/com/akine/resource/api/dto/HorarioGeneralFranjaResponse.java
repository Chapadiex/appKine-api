package com.akine.resource.api.dto;

import com.akine.resource.domain.FranjaHorarioGeneral.Franja;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonSerialize;

import java.time.LocalTime;

/** Una franja vigente del horario general de la sede (RF-M03-002, A-8). */
@Schema(description = "Franja semanal vigente del horario general de la sede")
public record HorarioGeneralFranjaResponse(

		@Schema(description = "Dia de la semana ISO-8601: 1 = lunes .. 7 = domingo", example = "1")
		int diaSemana,

		@Schema(description = "Hora local de apertura", type = "string", example = "09:00")
		@JsonSerialize(using = HoraDelDia.Serializador.class)
		LocalTime horaDesde,

		@Schema(description = "Hora local de cierre, EXCLUSIVA. La medianoche viaja como 24:00",
				type = "string", example = "13:00")
		@JsonSerialize(using = HoraDelDia.Serializador.class)
		LocalTime horaHasta) {

	public static HorarioGeneralFranjaResponse from(Franja franja) {
		return new HorarioGeneralFranjaResponse(
				franja.diaSemana(), franja.horaDesde(), franja.horaHasta());
	}
}
