package com.akine.activity.application;

import com.akine.activity.domain.AsistenciaEvento;

import java.time.Instant;

/**
 * Una entrada del historial append-only de una asistencia (RN-M28-009).
 *
 * <p>Es lo que hace auditable la correccion: el resultado anterior no se pierde al pisarse, queda
 * aca con su motivo, su actor y su instante.
 */
public record AsistenciaEventoView(
		long id,
		String tipo,
		String resultadoAnterior,
		String resultadoNuevo,
		String motivo,
		Long actorCuentaId,
		Instant ocurridoEn) {

	public static AsistenciaEventoView de(AsistenciaEvento evento) {
		return new AsistenciaEventoView(
				evento.getId(),
				evento.getTipo().name(),
				evento.getResultadoAnterior() == null ? null : evento.getResultadoAnterior().name(),
				evento.getResultadoNuevo().name(),
				evento.getMotivo(),
				evento.getActorCuentaId(),
				evento.getOcurridoEn());
	}
}
