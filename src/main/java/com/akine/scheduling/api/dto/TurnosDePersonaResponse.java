package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.TurnosDePersonaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "TurnosDePersona", description = "Una pagina de los turnos de una persona en una sede.")
public record TurnosDePersonaResponse(

		@Schema(description = "Zona de la sede, para mostrar las horas", example = "America/Argentina/Cordoba")
		String timezone,

		@Schema(description = "Total de turnos que cumplen el filtro", example = "12")
		long total,

		List<TurnoDeCalendarioResponse> turnos) {

	public static TurnosDePersonaResponse de(TurnosDePersonaView vista) {
		return new TurnosDePersonaResponse(vista.timezone(), vista.total(),
				vista.turnos().stream().map(TurnoDeCalendarioResponse::de).toList());
	}
}
