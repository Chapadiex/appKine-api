package com.akine.scheduling.application;

import com.akine.scheduling.domain.TurnoEvento;

import java.time.Instant;

/**
 * Una transicion del turno, tal como la muestra el historial (RF-M12-008).
 *
 * <p>Lleva el actor como id de cuenta y no como nombre: resolverlo obligaria a {@code scheduling} a
 * consultar {@code identity} por cada fila del historial. La pantalla ya resuelve nombres de cuenta
 * por su propio camino.
 *
 * @param estadoAnterior {@code null} en el evento de reserva, que es el unico sin estado previo
 * @param inicioAnterior no nulo solo en una reprogramacion: de donde vino el turno
 */
public record EventoDeTurnoView(
		long id,
		String tipo,
		String estadoAnterior,
		String estadoNuevo,
		String motivo,
		Instant inicioAnterior,
		Instant finAnterior,
		Instant inicioNuevo,
		Instant finNuevo,
		Long actorCuentaId,
		Instant ocurridoEn) {

	public static EventoDeTurnoView de(TurnoEvento evento) {
		return new EventoDeTurnoView(
				evento.getId(),
				evento.getTipo().name(),
				evento.getEstadoAnterior() == null ? null : evento.getEstadoAnterior().name(),
				evento.getEstadoNuevo().name(),
				evento.getMotivo(),
				evento.getInicioAnterior(),
				evento.getFinAnterior(),
				evento.getInicioNuevo(),
				evento.getFinNuevo(),
				evento.getActorCuentaId(),
				evento.getOcurridoEn());
	}
}
