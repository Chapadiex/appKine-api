package com.akine.scheduling.application;

import java.time.Instant;

/**
 * El indicador "reprogramado" de un turno. <b>No es un estado</b>: el estado operativo no cambia; esto
 * se deriva al leer de {@code turno_evento}, siguiendo la cadena de traslados ({@code turno_origen_id}).
 * Un traslado de sede cuenta como una reprogramacion.
 *
 * @param cantidad            reprogramaciones del turno y de sus turnos de origen, mas una por cada
 *                            traslado de la cadena. Siempre mayor que cero
 * @param inicioOriginal      el horario con el que se reservo el turno original de la cadena
 * @param ultimoTipo          {@code REPROGRAMACION} o {@code TRASLADO}
 * @param ultimaEn            cuando ocurrio la ultima
 * @param ultimoMotivo        su motivo declarado
 * @param ultimoActorCuentaId quien la hizo, o {@code null} si no consta
 * @param ultimoActorNombre   su nombre, o {@code null} si no resuelve
 */
public record ReprogramacionDeTurnoView(
		int cantidad,
		Instant inicioOriginal,
		String ultimoTipo,
		Instant ultimaEn,
		String ultimoMotivo,
		Long ultimoActorCuentaId,
		String ultimoActorNombre) {

	ReprogramacionDeTurnoView conActorNombre(String nombre) {
		return new ReprogramacionDeTurnoView(cantidad, inicioOriginal, ultimoTipo, ultimaEn, ultimoMotivo,
				ultimoActorCuentaId, nombre);
	}
}
