package com.akine.person.application;

import com.akine.person.domain.AutorizacionEvento;

import java.time.Instant;

/** Un hecho del historial de una autorizacion: que paso, de que estado a cual, quien y cuando. */
public record EventoDeAutorizacionView(
		long id,
		String tipo,
		String estadoAnterior,
		String estadoNuevo,
		boolean activa,
		Integer cantidad,
		Long movimientoId,
		String detalle,
		String motivo,
		Long consultorioId,
		Long actorCuentaId,
		Instant ocurridoEn) {

	public static EventoDeAutorizacionView de(AutorizacionEvento evento) {
		return new EventoDeAutorizacionView(
				evento.getId(),
				evento.getTipo().name(),
				evento.getEstadoAnterior() == null ? null : evento.getEstadoAnterior().name(),
				evento.getEstadoNuevo().name(),
				evento.isActiva(),
				evento.getCantidad(),
				evento.getMovimientoId(),
				evento.getDetalle(),
				evento.getMotivo(),
				evento.getConsultorioId(),
				evento.getActorCuentaId(),
				evento.getOcurridoEn());
	}
}
