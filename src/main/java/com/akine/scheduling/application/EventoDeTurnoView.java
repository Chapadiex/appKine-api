package com.akine.scheduling.application;

import com.akine.scheduling.domain.TurnoEvento;

import java.time.Instant;

/**
 * Una transicion del turno, tal como la muestra el historial (RF-M12-008).
 *
 * <p>Lleva el id de la cuenta y su nombre: el historial tiene que decir quien hizo cada cosa (TURNO -
 * Tecnico §47), y un numero de cuenta no se lo dice a nadie. Los nombres se resuelven en lote, una
 * consulta por historial.
 *
 * @param estadoAnterior {@code null} en el evento de reserva, que es el unico sin estado previo
 * @param inicioAnterior no nulo solo en una reprogramacion: de donde vino el turno
 * @param actorNombre {@code null} si lo hizo el sistema o la cuenta ya no resuelve
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
		String actorNombre,
		Instant ocurridoEn) {

	public static EventoDeTurnoView de(TurnoEvento evento, String actorNombre) {
		return new EventoDeTurnoView(
				evento.getId(),
				evento.getTipo().name(),
				evento.getEstadoAnterior(),
				evento.getEstadoNuevo(),
				evento.getMotivo(),
				evento.getInicioAnterior(),
				evento.getFinAnterior(),
				evento.getInicioNuevo(),
				evento.getFinNuevo(),
				evento.getActorCuentaId(),
				actorNombre,
				evento.getOcurridoEn());
	}
}
