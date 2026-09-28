package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.AgendaUnificadaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/** La grilla de una sede en un dia: turnos y clases juntos (RF-M12-011). */
@Schema(
		name = "AgendaUnificada",
		description = "Turnos y clases de una sede en un dia local, ordenados por hora.")
public record AgendaUnificadaResponse(

		@Schema(example = "2026-10-05") LocalDate fecha,

		@Schema(
				description = "Zona IANA de la sede. Los instantes van en UTC: sin esta zona la "
						+ "pantalla usa la del navegador y corre la grilla entera sin fallar.",
				example = "America/Argentina/Cordoba")
		String timezone,

		List<EventoDeAgendaResponse> eventos) {

	public static AgendaUnificadaResponse de(AgendaUnificadaView view) {
		return new AgendaUnificadaResponse(
				view.fecha(),
				view.timezone(),
				view.eventos().stream().map(EventoDeAgendaResponse::de).toList());
	}
}
