package com.akine.activity.api.dto;

import com.akine.activity.application.AsistenciaEventoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Una entrada del historial append-only de una asistencia (RN-M28-009).
 *
 * <p>Es lo que hace auditable la correccion: el resultado anterior no se pierde al pisarse.
 */
@Schema(
		name = "EventoDeAsistencia",
		description = "Registro o correccion. El historial no se actualiza ni se borra.")
public record AsistenciaEventoResponse(

		@Schema(example = "4411") long id,
		@Schema(description = "REGISTRO o CORRECCION", example = "CORRECCION") String tipo,
		@Schema(description = "null en el registro inicial", example = "AUSENTE")
		String resultadoAnterior,
		@Schema(example = "PRESENTE") String resultadoNuevo,
		@Schema(description = "Obligatorio en una correccion") String motivo,
		Long actorCuentaId,
		Instant ocurridoEn) {

	public static AsistenciaEventoResponse de(AsistenciaEventoView view) {
		return new AsistenciaEventoResponse(
				view.id(), view.tipo(), view.resultadoAnterior(), view.resultadoNuevo(),
				view.motivo(), view.actorCuentaId(), view.ocurridoEn());
	}
}
