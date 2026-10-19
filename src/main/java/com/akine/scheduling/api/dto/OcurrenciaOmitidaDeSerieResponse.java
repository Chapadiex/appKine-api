package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.OcurrenciaOmitidaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

/** Una ocurrencia de la regla que el alta de la serie no reservo (U-22). */
@Schema(
		name = "OcurrenciaOmitidaDeSerie",
		description = "Ocurrencia que la regla producia y que el alta no reservo, con su motivo. No es "
				+ "un turno: nunca existio.")
public record OcurrenciaOmitidaDeSerieResponse(

		@Schema(description = "Instante que la regla producia", example = "2026-12-07T12:00:00Z")
		Instant inicio,

		@Schema(description = "Fecha local de la ocurrencia, en la zona de la serie", example = "2026-12-07")
		LocalDate fecha,

		// CUIDADO: lista escrita a mano. OpenApiContractIT verifica que no se quede corta respecto
		// de OcurrenciaOmitida.Motivo.
		@Schema(
				description = "`FERIADO`: la sede cierra ese dia por feriado y ninguna apertura lo reabre.",
				allowableValues = {"FERIADO"},
				example = "FERIADO")
		String motivo) {

	public static OcurrenciaOmitidaDeSerieResponse de(OcurrenciaOmitidaView vista) {
		return new OcurrenciaOmitidaDeSerieResponse(vista.inicio(), vista.fecha(), vista.motivo());
	}
}
