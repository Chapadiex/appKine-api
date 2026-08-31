package com.akine.scheduling.domain;

import java.time.LocalTime;

/**
 * Un hueco concreto y reservable dentro de una franja de atencion, en hora local de la sede.
 *
 * <p><b>No se persiste.</b> RN-M12 y el diseno de la etapa lo fijan: la disponibilidad se
 * recalcula al leer, nunca se materializa. Un slot guardado envejece —cambia el horario, entra
 * un feriado, se desvincula el profesional— y a partir de ahi la agenda ofrece huecos que ya no
 * existen. Lo unico que se persiste es la RESERVA, que llega en 05.02.
 *
 * <p>Vive en hora local por el mismo motivo que {@code IntervaloLocal}: la aritmetica de slots
 * ocurre entera dentro de un dia y meterle el huso adentro es como entran los errores de horario
 * de verano. La conversion a {@code Instant} la hace la capa de aplicacion, al final y una sola
 * vez.
 *
 * @param profesionalId membership del profesional que lo atiende, o {@code null} si la oferta no
 *                      requiere profesional
 * @param espacioId     espacio asignado, o {@code null} si la oferta no requiere espacio
 * @param cupoTotal     cuantas reservas admite. Es {@code oferta.capacidad}: 1 para individual,
 *                      mas para grupal
 * @param cupoLibre     cuantas quedan. Hoy siempre igual a {@code cupoTotal} porque todavia no
 *                      existen los turnos; lo llena {@link com.akine.scheduling.spi.ReservaProbe}
 *                      cuando 05.02 lo implemente
 */
public record Slot(
		LocalTime desde,
		LocalTime hasta,
		Long profesionalId,
		Long espacioId,
		int cupoTotal,
		int cupoLibre) {

	public Slot {
		if (!hasta.isAfter(desde)) {
			throw new IllegalArgumentException("Un slot termina despues de empezar: " + desde + " -> " + hasta);
		}
		if (cupoTotal < 1) {
			throw new IllegalArgumentException("El cupo total de un slot es al menos 1: " + cupoTotal);
		}
		if (cupoLibre < 0 || cupoLibre > cupoTotal) {
			throw new IllegalArgumentException(
					"El cupo libre esta fuera de rango: " + cupoLibre + " de " + cupoTotal);
		}
	}

	public boolean hayLugar() {
		return cupoLibre > 0;
	}
}
