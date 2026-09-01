package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.EventoDeTurnoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Una transicion del turno (RF-M12-008).
 *
 * <p>El historial es <b>append-only</b>: no existe endpoint para editarlo ni para borrarlo, y
 * tampoco existe la operacion del lado del repositorio.
 */
@Schema(
		name = "EventoDeTurno",
		description = "Transicion registrada de un turno: quien, cuando, de que estado a cual y "
				+ "con que motivo.")
public record EventoDeTurnoResponse(

		@Schema(example = "9001")
		long id,

		@Schema(
				description = "RESERVA y REPROGRAMACION terminan las dos en RESERVADO; el tipo es lo "
						+ "que las distingue.",
				allowableValues = {"RESERVA", "CONFIRMACION", "CANCELACION", "REPROGRAMACION", "AUSENCIA"},
				example = "CANCELACION")
		String tipo,

		@Schema(description = "Ausente solo en el evento de RESERVA.", example = "CONFIRMADO")
		String estadoAnterior,

		@Schema(example = "CANCELADO")
		String estadoNuevo,

		@Schema(description = "Obligatorio en cancelacion y reprogramacion.", example = "El paciente aviso que no puede venir")
		String motivo,

		@Schema(description = "Solo en una reprogramacion: de donde vino el turno.", example = "2026-09-15T12:00:00Z")
		Instant inicioAnterior,

		@Schema(example = "2026-09-15T12:45:00Z")
		Instant finAnterior,

		@Schema(example = "2026-09-22T12:00:00Z")
		Instant inicioNuevo,

		@Schema(example = "2026-09-22T12:45:00Z")
		Instant finNuevo,

		@Schema(
				description = "Cuenta que lo hizo. Ausente cuando lo hizo el sistema, o —en los "
						+ "eventos reconstruidos por la migracion V38— cuando el dato no existia.",
				example = "12")
		Long actorCuentaId,

		@Schema(example = "2026-09-10T11:20:00Z")
		Instant ocurridoEn) {

	public static EventoDeTurnoResponse de(EventoDeTurnoView vista) {
		return new EventoDeTurnoResponse(
				vista.id(), vista.tipo(), vista.estadoAnterior(), vista.estadoNuevo(),
				vista.motivo(), vista.inicioAnterior(), vista.finAnterior(),
				vista.inicioNuevo(), vista.finNuevo(), vista.actorCuentaId(), vista.ocurridoEn());
	}
}
