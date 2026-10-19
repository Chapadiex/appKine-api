package com.akine.encounter.api.dto;

import com.akine.encounter.application.PoliticaDeEnmiendaService.PoliticaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** La ventana para enmendar que rige en la organizacion (DP-43, U-25). */
@Schema(
		name = "PoliticaDeEnmienda",
		description = "Dias desde el cierre en que una sesion se puede enmendar. Rige tambien para "
				+ "las sesiones ya cerradas.")
public record PoliticaDeEnmiendaResponse(

		@Schema(example = "30")
		int ventanaDias,

		@Schema(description = "`true` si la organizacion nunca fijo la suya y rige el valor por "
				+ "defecto, 30 dias. Entonces `version` viene nula.", example = "true")
		boolean porDefecto,

		@Schema(description = "Version para el proximo cambio. Nula mientras rige el valor por "
				+ "defecto.", example = "0")
		Long version,

		@Schema(description = "Cuando se fijo por ultima vez. Ausente con el valor por defecto.",
				example = "2026-10-19T14:00:00Z")
		Instant actualizadaEn) {

	public static PoliticaDeEnmiendaResponse de(PoliticaView vista) {
		return new PoliticaDeEnmiendaResponse(
				vista.ventanaDias(), vista.porDefecto(), vista.version(), vista.actualizadaEn());
	}
}
