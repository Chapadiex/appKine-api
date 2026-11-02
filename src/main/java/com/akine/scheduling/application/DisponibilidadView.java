package com.akine.scheduling.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Horarios libres de una prestacion, ya filtrados y con nombres (TURNO - Tecnico §12).
 *
 * @param buscadoHasta      ultimo dia EXCLUSIVO que se miro: la busqueda corta al llegar al limite
 * @param truncado          {@code true} si habia mas horarios que el limite pedido
 * @param motivosSinHorario por que hubo dias vacios, sin repetir, para explicar una busqueda vacia
 */
public record DisponibilidadView(
		long ofertaId,
		String ofertaNombre,
		int duracionMinutos,
		boolean requiereEspacio,
		String timezone,
		LocalDate buscadoHasta,
		boolean truncado,
		List<String> motivosSinHorario,
		List<HorarioDisponibleView> horarios) {

	/** @param espacioId box que tomaria si se reservara ahora: sugerencia, lo clava la reserva */
	public record HorarioDisponibleView(
			Instant desde,
			Instant hasta,
			Long profesionalId,
			String profesionalNombre,
			Long espacioId,
			String espacioNombre,
			int cupoLibre) {
	}
}
