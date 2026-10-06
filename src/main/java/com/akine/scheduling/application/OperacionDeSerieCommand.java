package com.akine.scheduling.application;

import com.akine.scheduling.domain.AlcanceDeSerie;

import java.time.Instant;

/**
 * Cancelacion o reprogramacion con alcance sobre una serie (AKINE E-3).
 *
 * <p>{@code cantidadConfirmada} es la confirmacion explicita de DP-04: la cantidad de turnos que el
 * operador vio en la previsualizacion. Si bajo el lock no coincide, no se toca nada.
 *
 * @param inicio        solo en una reprogramacion: el horario nuevo DEL PIVOTE
 * @param profesionalId solo en una reprogramacion, opcional: el profesional de todos los afectados
 */
public record OperacionDeSerieCommand(
		AlcanceDeSerie alcance,
		Long turnoId,
		String motivo,
		int cantidadConfirmada,
		Instant inicio,
		Long profesionalId) {

	public OperacionDeSerieCommand {
		if (alcance == null) {
			throw new IllegalArgumentException("El alcance es obligatorio");
		}
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("El motivo es obligatorio (DP-04)");
		}
		if (alcance != AlcanceDeSerie.TODA_LA_SERIE && turnoId == null) {
			throw new IllegalArgumentException(
					"El alcance " + alcance + " se cuenta desde un turno: falta turnoId");
		}
		motivo = motivo.strip();
	}

	public static OperacionDeSerieCommand cancelacion(
			AlcanceDeSerie alcance, Long turnoId, String motivo, int cantidadConfirmada) {
		return new OperacionDeSerieCommand(alcance, turnoId, motivo, cantidadConfirmada, null, null);
	}
}
