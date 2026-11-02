package com.akine.scheduling.application;

import java.time.LocalDate;
import java.util.List;

/**
 * Los turnos de una sede en un rango de fechas locales (DP-45, punto 2), para el calendario.
 *
 * @param desde    primer dia, inclusivo, en la zona de la sede
 * @param hasta    ultimo dia, EXCLUSIVO, en la zona de la sede
 * @param timezone zona de la sede; viaja siempre, tambien con el rango vacio
 */
public record TurnosDeSedeView(
		LocalDate desde,
		LocalDate hasta,
		String timezone,
		List<TurnoDeCalendarioView> turnos) {
}
