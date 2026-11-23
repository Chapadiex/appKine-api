package com.akine.assistant.spi;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Objects;

/**
 * Un rango de dias de la sede, {@code desde} y {@code hasta} inclusive, para las herramientas del agente.
 *
 * <p>{@link #resolver} convierte lo que manda el modelo ({@code YYYY-MM-DD} o una palabra) a dias de la
 * zona de la consulta: "hoy" lo fija el backend, nunca el modelo.
 */
public record Periodo(LocalDate desde, LocalDate hasta) {

	public Periodo {
		Objects.requireNonNull(desde, "desde");
		Objects.requireNonNull(hasta, "hasta");
		if (hasta.isBefore(desde)) {
			throw new IllegalArgumentException("El periodo termina antes de empezar");
		}
	}

	public static Periodo delDia(LocalDate dia) {
		return new Periodo(dia, dia);
	}

	/** Los ultimos {@code dias} dias, hoy incluido. */
	public static Periodo ultimosDias(LocalDate hoy, int dias) {
		return new Periodo(hoy.minusDays(dias - 1L), hoy);
	}

	public LocalDate hastaExclusivo() {
		return hasta.plusDays(1);
	}

	public Instant desdeInstante(ZoneId zona) {
		return desde.atStartOfDay(zona).toInstant();
	}

	public Instant hastaInstante(ZoneId zona) {
		return hastaExclusivo().atStartOfDay(zona).toInstant();
	}

	/**
	 * @param desde      {@code null}, {@code YYYY-MM-DD} o {@code hoy|ayer|manana|esta_semana|semana_pasada|
	 *                   proxima_semana|este_mes|mes_pasado}
	 * @param hasta      igual que {@code desde}; con {@code null} se toma el fin del periodo de {@code desde}
	 * @param porDefecto el periodo si no vienen ninguno de los dos
	 * @throws IllegalArgumentException si un valor no se entiende: la herramienta lo devuelve al modelo como
	 *                                  error para que lo corrija
	 */
	public static Periodo resolver(Object desde, Object hasta, ContextoDeConsulta ctx, Periodo porDefecto) {
		if (vacio(desde) && vacio(hasta)) {
			return porDefecto;
		}
		Periodo inicio = vacio(desde) ? null : de(String.valueOf(desde), ctx.hoy());
		Periodo fin = vacio(hasta) ? null : de(String.valueOf(hasta), ctx.hoy());
		if (inicio == null) {
			return new Periodo(fin.desde(), fin.hasta());
		}
		return new Periodo(inicio.desde(), fin == null ? inicio.hasta() : fin.hasta());
	}

	private static boolean vacio(Object valor) {
		return valor == null || String.valueOf(valor).isBlank();
	}

	private static Periodo de(String valor, LocalDate hoy) {
		String v = valor.strip().toLowerCase(Locale.ROOT).replace(' ', '_').replace('ñ', 'n');
		LocalDate lunes = hoy.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		return switch (v) {
			case "hoy" -> delDia(hoy);
			case "ayer" -> delDia(hoy.minusDays(1));
			case "manana" -> delDia(hoy.plusDays(1));
			case "esta_semana" -> new Periodo(lunes, lunes.plusDays(6));
			case "semana_pasada" -> new Periodo(lunes.minusWeeks(1), lunes.minusDays(1));
			case "proxima_semana" -> new Periodo(lunes.plusWeeks(1), lunes.plusDays(13));
			case "este_mes" -> new Periodo(hoy.withDayOfMonth(1), hoy.with(TemporalAdjusters.lastDayOfMonth()));
			case "mes_pasado" -> {
				LocalDate primero = hoy.minusMonths(1).withDayOfMonth(1);
				yield new Periodo(primero, primero.with(TemporalAdjusters.lastDayOfMonth()));
			}
			default -> {
				try {
					yield delDia(LocalDate.parse(v));
				}
				catch (DateTimeParseException e) {
					throw new IllegalArgumentException("Fecha no reconocida: '" + valor
							+ "'. Usá YYYY-MM-DD o hoy, ayer, esta_semana, semana_pasada, este_mes, mes_pasado");
				}
			}
		};
	}
}
