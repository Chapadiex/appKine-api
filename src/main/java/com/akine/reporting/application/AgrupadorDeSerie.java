package com.akine.reporting.application;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pasa los valores por dia de un contribuyente a los puntos que dibuja el grafico (DP-45).
 *
 * <p>Completa con <b>cero explicito</b> los dias o semanas sin actividad: un hueco en la serie se
 * dibuja como una barra que falta, y el operador no distingue "no hubo" de "no se calculo".
 *
 * <p>Sumar dias en semanas es valido porque solo se publican series de indicadores sumables
 * (dinero y conteos con fecha, ver {@code SerieDiaria}).
 */
final class AgrupadorDeSerie {

	private AgrupadorDeSerie() {
	}

	/** Cuantos puntos produce el periodo con esa agrupacion. Se valida contra el tope antes de calcular. */
	static long cantidadDePuntos(LocalDate desde, LocalDate hasta, AgrupacionDeSerie agrupacion) {
		if (agrupacion == AgrupacionDeSerie.DIA) {
			return ChronoUnit.DAYS.between(desde, hasta) + 1;
		}
		LocalDate primerLunes = desde.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		LocalDate ultimoLunes = hasta.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		return ChronoUnit.WEEKS.between(primerLunes, ultimoLunes) + 1;
	}

	static List<SerieDeReporteView.Punto> agrupar(
			LocalDate desde, LocalDate hasta, AgrupacionDeSerie agrupacion,
			Map<LocalDate, BigDecimal> porDia) {

		List<SerieDeReporteView.Punto> puntos = new ArrayList<>();
		LocalDate inicio = desde;
		while (!inicio.isAfter(hasta)) {
			LocalDate fin = agrupacion == AgrupacionDeSerie.DIA
					? inicio
					: min(inicio.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)), hasta);
			BigDecimal valor = BigDecimal.ZERO;
			for (LocalDate dia = inicio; !dia.isAfter(fin); dia = dia.plusDays(1)) {
				BigDecimal delDia = porDia.get(dia);
				if (delDia != null) {
					valor = valor.add(delDia);
				}
			}
			puntos.add(new SerieDeReporteView.Punto(inicio, fin, valor));
			inicio = fin.plusDays(1);
		}
		return puntos;
	}

	private static LocalDate min(LocalDate a, LocalDate b) {
		return a.isBefore(b) ? a : b;
	}
}
