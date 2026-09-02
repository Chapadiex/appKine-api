package com.akine.scheduling.application;

import java.time.LocalDate;
import java.util.List;

/**
 * Los turnos de una sede en un dia, con la zona con la que hay que mostrarlos.
 *
 * <p>La zona no es decoracion: los instantes van en UTC y "las 09:00" es una hora local. Sin ella
 * la pantalla usa la del navegador, corre la agenda entera y <b>no falla</b> — muestra otra cosa,
 * que es peor.
 *
 * @param fecha el dia pedido, ya interpretado en la zona de la sede
 */
public record AgendaDelDiaView(
		LocalDate fecha,
		String timezone,
		List<TurnoDelDiaView> turnos) {
}
