package com.akine.activity.api.dto;

import com.akine.activity.application.EventoDeClaseView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Una linea del historial de una clase (RN-M28-009).
 *
 * <p>El intervalo y la capacidad <b>anteriores</b> viajan porque son lo que hace trazable una
 * reprogramacion sin recrear la clase: sin ellos, la unica evidencia de que la clase se movio seria
 * que la fecha actual no es la original, y nadie recuerda cual era la original.
 */
@Schema(name = "EventoDeClase", description = "Transicion registrada de una clase. Append-only.")
public record EventoDeClaseResponse(

		@Schema(example = "301") long id,
		@Schema(description = "CREACION, REPROGRAMACION o CANCELACION", example = "REPROGRAMACION")
		String tipo,
		String estadoAnterior,
		@Schema(example = "PROGRAMADA") String estadoNuevo,
		String motivo,
		Instant inicioAnterior,
		Instant finAnterior,
		Instant inicioNuevo,
		Instant finNuevo,
		Integer capacidadAnterior,
		Integer capacidadNueva,
		@Schema(description = "Cuenta que lo hizo. Nulo si lo hizo el sistema.") Long actorCuentaId,
		Instant ocurridoEn) {

	public static EventoDeClaseResponse de(EventoDeClaseView view) {
		return new EventoDeClaseResponse(
				view.id(),
				view.tipo(),
				view.estadoAnterior(),
				view.estadoNuevo(),
				view.motivo(),
				view.inicioAnterior(),
				view.finAnterior(),
				view.inicioNuevo(),
				view.finNuevo(),
				view.capacidadAnterior(),
				view.capacidadNueva(),
				view.actorCuentaId(),
				view.ocurridoEn());
	}
}
