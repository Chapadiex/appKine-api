package com.akine.scheduling.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

/**
 * Que horarios se estan buscando.
 *
 * @param profesionalId {@code null} = cualquier profesional habilitado (TURNO.md §14)
 * @param desde         {@code null} = hoy en la zona de la sede
 * @param hasta         EXCLUSIVO. {@code null} = hasta encontrar {@code limite} o agotar la ventana
 * @param dias          {@code null} o vacio = cualquier dia
 * @param limite        {@code null} = 20. Con 1 es "primer turno disponible" (TURNO.md §24)
 */
public record BusquedaDeDisponibilidad(
		long ofertaId,
		Long profesionalId,
		LocalDate desde,
		LocalDate hasta,
		LocalTime horaDesde,
		LocalTime horaHasta,
		Set<DayOfWeek> dias,
		Integer limite) {
}
