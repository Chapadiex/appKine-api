package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.ReprogramacionDeTurnoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Ver {@link ReprogramacionDeTurnoView}. */
@Schema(
		name = "ReprogramacionDeTurno",
		description = "Indicador \"reprogramado\" de un turno, derivado al leer de su historial y del de sus "
				+ "turnos de origen (cambio de sede). No es un estado.")
public record ReprogramacionDeTurnoResponse(

		@Schema(description = "Reprogramaciones del turno y de su cadena de traslados, mas una por traslado",
				example = "2")
		int cantidad,

		@Schema(description = "Horario con el que se reservo el turno original de la cadena",
				example = "2026-11-10T13:00:00Z")
		Instant inicioOriginal,

		@Schema(description = "Que fue la ultima", allowableValues = {"REPROGRAMACION", "TRASLADO"})
		String ultimoTipo,

		@Schema(description = "Cuando ocurrio la ultima", example = "2026-11-04T15:20:00Z")
		Instant ultimaEn,

		@Schema(description = "Motivo declarado de la ultima", example = "Licencia del profesional")
		String ultimoMotivo,

		@Schema(description = "Cuenta que la hizo. Ausente si no consta", example = "12")
		Long ultimoActorCuentaId,

		@Schema(description = "Nombre de esa cuenta. Ausente si no resuelve", example = "Ana Recepcion")
		String ultimoActorNombre) {

	public static ReprogramacionDeTurnoResponse de(ReprogramacionDeTurnoView vista) {
		return vista == null ? null : new ReprogramacionDeTurnoResponse(vista.cantidad(), vista.inicioOriginal(),
				vista.ultimoTipo(), vista.ultimaEn(), vista.ultimoMotivo(), vista.ultimoActorCuentaId(),
				vista.ultimoActorNombre());
	}
}
