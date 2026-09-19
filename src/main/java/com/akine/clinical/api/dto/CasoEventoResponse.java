package com.akine.clinical.api.dto;

import com.akine.clinical.application.CasoEventoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Una transicion del caso, tal como sale por la API (RF-M10-006).
 *
 * <p><b>{@code detalle} nunca lleva contenido clinico:</b> dice que se edito el objetivo, no cual
 * era. El diagnostico y el objetivo se leen del caso, con su propio acceso auditado.
 *
 * <p>Esto no es la auditoria y no la reemplaza: {@code audit_event} registra <b>accesos</b> y lo
 * lee quien audita, con {@code auditoria:read}. Esto registra <b>estados del caso</b> y lo lee el
 * profesional que abre la ficha, con {@code hc:read}.
 */
@Schema(name = "CasoEvento",
		description = "Transicion asentada en el historial de un Caso Clinico. Append-only")
public record CasoEventoResponse(

		@Schema(example = "512")
		long id,

		@Schema(allowableValues = {"APERTURA", "EDICION", "CIERRE", "REAPERTURA",
				"CAMBIO_DE_EQUIPO"}, example = "CIERRE")
		String tipo,

		@Schema(description = "Estado antes de la transicion. Ausente solo en APERTURA: antes no "
				+ "habia estado", allowableValues = {"ACTIVO", "CERRADO"})
		String estadoAnterior,

		@Schema(allowableValues = {"ACTIVO", "CERRADO"}, example = "CERRADO")
		String estadoNuevo,

		@Schema(description = "Presente en CIERRE y REAPERTURA, que lo exigen. Ausente en el resto",
				example = "Alta por objetivos cumplidos")
		String motivo,

		@Schema(description = "Resumen NO CLINICO de que cambio. Nunca trae diagnostico ni objetivo",
				example = "Entraron 1, salieron 0")
		String detalle,

		@Schema(example = "2026-09-19T15:40:12Z")
		Instant ocurrioEn,

		@Schema(description = "Cuenta que produjo la transicion", example = "8")
		long actorCuentaId) {

	public static CasoEventoResponse from(CasoEventoView view) {
		return new CasoEventoResponse(
				view.id(),
				view.tipo(),
				view.estadoAnterior(),
				view.estadoNuevo(),
				view.motivo(),
				view.detalle(),
				view.ocurrioEn(),
				view.actorCuentaId());
	}
}
