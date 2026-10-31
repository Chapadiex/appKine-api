package com.akine.scheduling.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Todo lo que dibuja la agenda de una sede en una ventana (TURNO - Tecnico §14 y §55): quien atiende y
 * cuando, en que boxes, y que hay encima.
 */
public record AgendaOperativaView(
		LocalDate desde,
		LocalDate hasta,
		String timezone,
		List<ProfesionalDeAgendaView> profesionales,
		List<EspacioDeAgendaView> espacios,
		List<TurnoDeCalendarioView> turnos,
		List<ClaseEnAgendaView> clases,
		List<BloqueoEnAgendaView> bloqueos) {

	/** @param franjas cuando atiende: lo que no esta aca no se puede reservar */
	public record ProfesionalDeAgendaView(long membershipId, String nombre, List<FranjaDeAgendaView> franjas) {
	}

	public record FranjaDeAgendaView(Instant desde, Instant hasta) {
	}

	public record EspacioDeAgendaView(long id, String nombre, String tipo) {
	}

	public record ClaseEnAgendaView(
			long claseId, Instant inicio, Instant fin, String estado, String titulo, String ofertaNombre,
			Long profesionalId, Long espacioId, int capacidad, int ocupados) {
	}

	/**
	 * Un cierre, un tramo por dia.
	 *
	 * @param excepcionId el cierre: se da de baja una sola vez aunque abarque varios dias
	 * @param membershipId {@code null} = sede entera
	 */
	public record BloqueoEnAgendaView(
			long excepcionId, Long membershipId, String motivo, String notas, Instant desde, Instant hasta) {
	}
}
