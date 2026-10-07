package com.akine.scheduling.application;

import com.akine.scheduling.domain.EstadoDeSerie;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Una fila de la bandeja de series de una sede (AKINE E-8).
 *
 * <p>Lleva la regla resumida —dias, hora, desde y fin— y la foto de sus turnos HOY: cuantos
 * genero, cuantos quedan pendientes y cuando es el proximo. El estado es derivado de esos turnos
 * ({@link EstadoDeSerie}). PHI minima, la misma que la agenda del dia: nombre y documento del
 * paciente para reconocerlo, nada clinico.
 *
 * @param totalTurnos       turnos de la serie hoy, en cualquier estado
 * @param turnosPendientes  RESERVADO o CONFIRMADO, vivos y que todavia no empezaron
 * @param proximoTurnoInicio inicio del proximo pendiente; {@code null} si no queda ninguno
 */
public record SerieResumenView(
		long id,
		long consultorioId,
		long personaId,
		String personaNombre,
		String documento,
		long ofertaId,
		String ofertaNombre,
		Long profesionalId,
		String frecuencia,
		List<Integer> diasSemana,
		LocalTime hora,
		LocalDate fechaDesde,
		LocalDate fechaHasta,
		Integer cantidad,
		String timezone,
		Instant creadaEn,
		int totalTurnos,
		int turnosPendientes,
		Instant proximoTurnoInicio,
		EstadoDeSerie estado) {
}
