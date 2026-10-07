package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.EventoDeRecepcionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Una transicion de la recepcion. El historial es append-only. */
@Schema(
		name = "EventoDeRecepcion",
		description = "Transicion registrada de una recepcion: quien, cuando, de que estado a cual y "
				+ "con que motivo (DP-05).")
public record EventoDeRecepcionResponse(

		@Schema(example = "5001")
		long id,

		@Schema(description = "Recepcion a la que pertenece. Un turno puede tener varias si se anulo un check-in.", example = "77")
		long recepcionId,

		// CUIDADO: lista escrita a mano. OpenApiContractIT verifica que no se quede corta respecto
		// de TipoEventoRecepcion.
		@Schema(
				description = "`VALIDACION` la calcula el servidor; `PARTICULAR` la decide el operador. "
						+ "Las dos pueden terminar en VALIDADA.",
				allowableValues = {"LLEGADA", "VALIDACION", "PARTICULAR", "ESPERA", "LLAMADO",
						"ANULACION", "CIERRE_POR_CANCELACION"},
				example = "LLEGADA")
		String tipo,

		@Schema(description = "Ausente solo en la LLEGADA.", example = "VALIDADA")
		String estadoAnterior,

		@Schema(example = "EN_ESPERA")
		String estadoNuevo,

		@Schema(example = "Sin orden medica; el paciente prefiere abonar")
		String motivo,

		@Schema(example = "12")
		Long actorCuentaId,

		@Schema(example = "2026-09-15T11:52:00Z")
		Instant ocurridoEn) {

	public static EventoDeRecepcionResponse de(EventoDeRecepcionView vista) {
		return new EventoDeRecepcionResponse(
				vista.id(), vista.recepcionId(), vista.tipo(), vista.estadoAnterior(),
				vista.estadoNuevo(), vista.motivo(), vista.actorCuentaId(), vista.ocurridoEn());
	}
}
