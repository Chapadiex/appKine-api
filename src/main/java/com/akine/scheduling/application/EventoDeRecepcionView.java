package com.akine.scheduling.application;

import com.akine.scheduling.domain.RecepcionEvento;

import java.time.Instant;

/** Una transicion de la recepcion: quien, cuando, de que estado a cual y por que. */
public record EventoDeRecepcionView(
		long id,
		long recepcionId,
		String tipo,
		String estadoAnterior,
		String estadoNuevo,
		String motivo,
		Long actorCuentaId,
		Instant ocurridoEn) {

	public static EventoDeRecepcionView de(RecepcionEvento evento) {
		return new EventoDeRecepcionView(
				evento.getId(),
				evento.getRecepcionId(),
				evento.getTipo().name(),
				evento.getEstadoAnterior() == null ? null : evento.getEstadoAnterior().name(),
				evento.getEstadoNuevo().name(),
				evento.getMotivo(),
				evento.getActorCuentaId(),
				evento.getOcurridoEn());
	}
}
