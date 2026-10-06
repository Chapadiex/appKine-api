package com.akine.scheduling.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Regla semanal de una serie de turnos (AKINE E-3, DP-04).
 *
 * <p>Pura: no sabe de zonas ni de agenda. Expande la regla en fechas y horas <b>locales</b>; el
 * servicio las convierte con la zona de la sede y las reserva una por una bajo el lock.
 *
 * <p>Termina por {@code cantidad} o por {@code hasta}, exactamente uno de los dos, y nunca produce
 * mas de {@link #MAXIMO_OCURRENCIAS}: sin tope, un error de tipeo en la fecha fin tomaria el lock de
 * la sede para reservar cientos de turnos.
 */
public record ReglaDeRecurrencia(
		Set<DayOfWeek> dias,
		LocalTime hora,
		LocalDate desde,
		LocalDate hasta,
		Integer cantidad) {

	/** Un ano de sesiones semanales. Decision a revisar (diseno E-3, §10.2). */
	public static final int MAXIMO_OCURRENCIAS = 52;

	public ReglaDeRecurrencia {
		if (dias == null || dias.isEmpty()) {
			throw new IllegalArgumentException("La serie necesita al menos un dia de la semana");
		}
		if (hora == null || desde == null) {
			throw new IllegalArgumentException("La serie necesita hora y fecha de inicio");
		}
		if ((cantidad == null) == (hasta == null)) {
			throw new IllegalArgumentException(
					"La serie termina por cantidad o por fecha fin: exactamente uno de los dos");
		}
		if (cantidad != null && (cantidad < 1 || cantidad > MAXIMO_OCURRENCIAS)) {
			throw new IllegalArgumentException(
					"La cantidad de turnos de una serie va de 1 a " + MAXIMO_OCURRENCIAS);
		}
		if (hasta != null && hasta.isBefore(desde)) {
			throw new IllegalArgumentException("La fecha fin de la serie es anterior a la de inicio");
		}
		dias = Collections.unmodifiableSet(EnumSet.copyOf(dias));
	}

	/**
	 * Las ocurrencias en hora local, en orden cronologico.
	 *
	 * @throws IllegalArgumentException si la regla no produce ninguna o produce mas del maximo
	 */
	public List<LocalDateTime> ocurrencias() {
		List<LocalDateTime> resultado = new ArrayList<>();
		for (LocalDate fecha = desde; hasta == null || !fecha.isAfter(hasta); fecha = fecha.plusDays(1)) {
			if (dias.contains(fecha.getDayOfWeek())) {
				if (resultado.size() == MAXIMO_OCURRENCIAS) {
					throw new IllegalArgumentException(
							"La serie produce mas de " + MAXIMO_OCURRENCIAS + " turnos: acorta la fecha fin");
				}
				resultado.add(fecha.atTime(hora));
				if (cantidad != null && resultado.size() == cantidad) {
					break;
				}
			}
		}
		if (resultado.isEmpty()) {
			throw new IllegalArgumentException(
					"La serie no produce ningun turno entre " + desde + " y " + hasta);
		}
		return resultado;
	}

	/** Dias ISO separados por coma, de lunes a domingo: {@code "1,4"}. Es lo que guarda V70. */
	public String diasComoTexto() {
		return dias.stream()
				.sorted()
				.map(dia -> String.valueOf(dia.getValue()))
				.collect(Collectors.joining(","));
	}

	public static Set<DayOfWeek> diasDesdeTexto(String texto) {
		return Arrays.stream(texto.split(","))
				.map(String::strip)
				.map(Integer::parseInt)
				.map(DayOfWeek::of)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
	}
}
