package com.akine.activity.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * Lo que hay que decir para programar clases semanales: los mismos datos de una clase suelta,
 * repetidos en ciertos dias de la semana dentro de un rango de fechas.
 *
 * @param desde          primer dia del rango, inclusive, en la zona de la sede
 * @param hasta          ultimo dia del rango, <b>inclusive</b>: "hasta el 31/12" incluye el 31
 * @param diasSemana     ISO-8601, lunes = 1 .. domingo = 7, la misma convencion que la carga en lote
 *                       de la disponibilidad
 * @param horaInicio     hora local de inicio, la misma todos los dias
 * @param horaFin        hora local de fin, EXCLUSIVA; tiene que ser posterior a la de inicio (una clase
 *                       no cruza la medianoche)
 * @param idempotencyKey clave del cliente para la serie entera. Cada fecha usa
 *                       {@code clave + ":" + fecha}, asi un reintento devuelve las clases ya creadas en
 *                       vez de chocar contra ellas
 */
public record ProgramarSerieDeClasesCommand(
		LocalDate desde,
		LocalDate hasta,
		Set<Integer> diasSemana,
		LocalTime horaInicio,
		LocalTime horaFin,
		Long profesionalId,
		int capacidad,
		String titulo,
		String idempotencyKey,
		Long espacioId) {

	/**
	 * Tope del rango: un anio. Mas que eso es un horario fijo, no una serie, y cada fecha toma el lock
	 * de la sede: una serie de cinco anios retendria la agenda de la sede minutos enteros.
	 */
	static final int MAXIMO_DIAS_DEL_RANGO = 366;

	public ProgramarSerieDeClasesCommand {
		FechasAdmitidas.exigir(desde, "desde");
		FechasAdmitidas.exigir(hasta, "hasta");
		if (desde == null || hasta == null) {
			throw new IllegalArgumentException("El rango de fechas es obligatorio");
		}
		if (hasta.isBefore(desde)) {
			throw new IllegalArgumentException("La fecha hasta no puede ser anterior a la fecha desde");
		}
		if (ChronoUnit.DAYS.between(desde, hasta) >= MAXIMO_DIAS_DEL_RANGO) {
			throw new IllegalArgumentException("Una serie de clases no puede abarcar mas de un anio");
		}
		if (diasSemana == null || diasSemana.isEmpty()) {
			throw new IllegalArgumentException("Hay que elegir al menos un dia de la semana");
		}
		if (diasSemana.stream().anyMatch(dia -> dia == null || dia < 1 || dia > 7)) {
			throw new IllegalArgumentException("El dia de la semana va de 1 (lunes) a 7 (domingo)");
		}
		if (horaInicio == null || horaFin == null) {
			throw new IllegalArgumentException("La hora de inicio y la de fin son obligatorias");
		}
		if (!horaFin.isAfter(horaInicio)) {
			throw new IllegalArgumentException("La hora de fin tiene que ser posterior a la de inicio");
		}
		diasSemana = Set.copyOf(diasSemana);
	}
}
