package com.akine.organization.api.dto;

import com.akine.organization.application.CobroDeSuscripcionService.CobroView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Un cobro de la suscripcion registrado por la plataforma")
public record CobroDeSuscripcionResponse(
		@Schema(description = "Identificador del cobro", example = "12")
		long id,
		@Schema(description = "Desde cuando cubre, instante UTC: el vencimiento anterior si todavia no habia "
				+ "llegado, o el momento del registro", example = "2026-11-19T15:00:00Z")
		Instant periodoDesde,
		@Schema(description = "Hasta cuando cubre, instante UTC: periodoDesde mas los meses, en la zona del "
				+ "centro. Es el nuevo pagadoHasta", example = "2026-12-19T15:00:00Z")
		Instant periodoHasta,
		@Schema(description = "Meses cobrados", example = "1")
		int meses,
		@Schema(description = "Nota libre de quien lo registro", example = "Transferencia del 19/11")
		String nota,
		@Schema(description = "Cuenta de plataforma que lo registro", example = "1")
		Long registradoPor,
		@Schema(description = "Instante del registro", example = "2026-11-19T15:00:00Z")
		Instant registradoEn) {

	public static CobroDeSuscripcionResponse from(CobroView view) {
		return new CobroDeSuscripcionResponse(view.id(), view.periodoDesde(), view.periodoHasta(), view.meses(),
				view.nota(), view.registradoPor(), view.registradoEn());
	}
}
