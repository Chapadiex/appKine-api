package com.akine.reporting.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DP-45: los dias de un contribuyente pasados a barras, con ceros explicitos y semanas recortadas. */
class AgrupadorDeSerieTest {

	private static final LocalDate MIERCOLES = LocalDate.of(2026, 10, 7);

	@Test
	@DisplayName("Por dia: un punto por dia del periodo, con cero donde no hubo actividad")
	void por_dia_completa_ceros() {
		List<SerieDeReporteView.Punto> puntos = AgrupadorDeSerie.agrupar(
				MIERCOLES, MIERCOLES.plusDays(2), AgrupacionDeSerie.DIA,
				Map.of(MIERCOLES.plusDays(1), new BigDecimal("100.50")));

		assertThat(puntos).extracting(SerieDeReporteView.Punto::desde)
				.containsExactly(MIERCOLES, MIERCOLES.plusDays(1), MIERCOLES.plusDays(2));
		assertThat(puntos).extracting(SerieDeReporteView.Punto::valor)
				.usingElementComparator(BigDecimal::compareTo)
				.containsExactly(BigDecimal.ZERO, new BigDecimal("100.50"), BigDecimal.ZERO);
	}

	@Test
	@DisplayName("Por semana: lunes a domingo, los extremos recortados al periodo, y nada de afuera suma")
	void por_semana_recorta_los_extremos() {
		// Del miercoles 7 al martes 20: semanas 7-11, 12-18 y 19-20.
		LocalDate hasta = MIERCOLES.plusDays(13);
		List<SerieDeReporteView.Punto> puntos = AgrupadorDeSerie.agrupar(
				MIERCOLES, hasta, AgrupacionDeSerie.SEMANA,
				Map.of(MIERCOLES.minusDays(1), new BigDecimal("999"),
						MIERCOLES, new BigDecimal("1"),
						LocalDate.of(2026, 10, 11), new BigDecimal("2"),
						LocalDate.of(2026, 10, 12), new BigDecimal("4"),
						hasta, new BigDecimal("8")));

		assertThat(puntos).hasSize(3);
		assertThat(puntos.get(0).desde()).isEqualTo(MIERCOLES);
		assertThat(puntos.get(0).hasta()).isEqualTo(LocalDate.of(2026, 10, 11));
		assertThat(puntos.get(1).desde()).isEqualTo(LocalDate.of(2026, 10, 12));
		assertThat(puntos.get(1).hasta()).isEqualTo(LocalDate.of(2026, 10, 18));
		assertThat(puntos.get(2).hasta()).isEqualTo(hasta);
		assertThat(puntos).extracting(SerieDeReporteView.Punto::valor)
				.usingElementComparator(BigDecimal::compareTo)
				.containsExactly(new BigDecimal("3"), new BigDecimal("4"), new BigDecimal("8"));
		assertThat(AgrupadorDeSerie.cantidadDePuntos(MIERCOLES, hasta, AgrupacionDeSerie.SEMANA))
				.isEqualTo(3);
	}

	@Test
	@DisplayName("La ventana maxima de 366 dias por semana entra en el tope; por dia, no")
	void cantidad_de_puntos_contra_el_tope() {
		LocalDate desde = LocalDate.of(2026, 1, 1);
		LocalDate hasta = desde.plusDays(365);

		assertThat(AgrupadorDeSerie.cantidadDePuntos(desde, hasta, AgrupacionDeSerie.SEMANA))
				.isLessThanOrEqualTo(ReporteService.MAXIMO_PUNTOS);
		assertThat(AgrupadorDeSerie.cantidadDePuntos(desde, hasta, AgrupacionDeSerie.DIA))
				.isEqualTo(366);
		assertThat(AgrupadorDeSerie.cantidadDePuntos(desde, desde.plusDays(99), AgrupacionDeSerie.DIA))
				.isEqualTo(ReporteService.MAXIMO_PUNTOS);
	}
}
