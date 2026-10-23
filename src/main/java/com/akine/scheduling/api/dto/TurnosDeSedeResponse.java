package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.TurnosDeSedeView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(
		name = "TurnosDeSede",
		description = "Los turnos reservados de una sede en un rango de fechas, con la zona con la "
				+ "que hay que mostrarlos. PHI minima: ningun dato clinico.")
public record TurnosDeSedeResponse(

		@Schema(description = "Primer dia, inclusivo, en la zona de la sede", example = "2026-10-26")
		LocalDate desde,

		@Schema(description = "Ultimo dia, EXCLUSIVO, en la zona de la sede", example = "2026-11-02")
		LocalDate hasta,

		@Schema(description = "Zona horaria de la sede", example = "America/Argentina/Cordoba")
		String timezone,

		@Schema(description = "Turnos que empiezan en el rango, por hora de inicio y despues por id")
		List<TurnoDeCalendarioResponse> turnos) {

	public static TurnosDeSedeResponse de(TurnosDeSedeView vista) {
		return new TurnosDeSedeResponse(
				vista.desde(), vista.hasta(), vista.timezone(),
				vista.turnos().stream().map(TurnoDeCalendarioResponse::de).toList());
	}
}
