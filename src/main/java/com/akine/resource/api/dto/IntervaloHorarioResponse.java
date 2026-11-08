package com.akine.resource.api.dto;

import com.akine.resource.domain.IntervaloLocal;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonSerialize;

import java.time.LocalTime;

/** Un tramo de horas locales de un dia, sin el dia: el horario de la sede o lo que queda de una franja. */
@Schema(description = "Tramo de horas locales de un dia")
public record IntervaloHorarioResponse(

		@Schema(description = "Hora local de inicio", type = "string", example = "09:00")
		@JsonSerialize(using = HoraDelDia.Serializador.class)
		LocalTime horaDesde,

		@Schema(description = "Hora local de fin, EXCLUSIVA. La medianoche viaja como 24:00",
				type = "string", example = "13:00")
		@JsonSerialize(using = HoraDelDia.Serializador.class)
		LocalTime horaHasta) {

	public static IntervaloHorarioResponse from(IntervaloLocal intervalo) {
		return new IntervaloHorarioResponse(intervalo.desde(), intervalo.hasta());
	}
}
