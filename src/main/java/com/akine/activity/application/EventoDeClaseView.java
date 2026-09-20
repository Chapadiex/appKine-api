package com.akine.activity.application;

import com.akine.activity.domain.ClaseEvento;

import java.time.Instant;

/** Una linea del historial de una clase (RN-M28-009). */
public record EventoDeClaseView(
		long id,
		String tipo,
		String estadoAnterior,
		String estadoNuevo,
		String motivo,
		Instant inicioAnterior,
		Instant finAnterior,
		Instant inicioNuevo,
		Instant finNuevo,
		Integer capacidadAnterior,
		Integer capacidadNueva,
		Long actorCuentaId,
		Instant ocurridoEn) {

	public static EventoDeClaseView de(ClaseEvento evento) {
		return new EventoDeClaseView(
				evento.getId(),
				evento.getTipo().name(),
				evento.getEstadoAnterior() == null ? null : evento.getEstadoAnterior().name(),
				evento.getEstadoNuevo().name(),
				evento.getMotivo(),
				evento.getInicioAnterior(),
				evento.getFinAnterior(),
				evento.getInicioNuevo(),
				evento.getFinNuevo(),
				evento.getCapacidadAnterior(),
				evento.getCapacidadNueva(),
				evento.getActorCuentaId(),
				evento.getOcurridoEn());
	}
}
