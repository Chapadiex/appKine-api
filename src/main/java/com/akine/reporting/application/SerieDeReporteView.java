package com.akine.reporting.application;

import com.akine.reporting.spi.AdvertenciaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Las series temporales de un reporte, ya agrupadas (DP-45).
 *
 * <p>Mismas {@code omitidas} y mismas {@code advertencias} de alcance que el reporte: una seccion
 * que el reporte omite, la serie tambien la omite y la declara.
 */
public record SerieDeReporteView(
		ReporteCode reporte,
		long consultorioId,
		LocalDate desde,
		LocalDate hasta,
		String zona,
		AgrupacionDeSerie agrupacion,
		Instant generadoEn,
		List<Serie> series,
		List<ReporteView.SeccionOmitida> omitidas,
		List<AdvertenciaDeReporte> advertencias) {

	public SerieDeReporteView {
		series = series == null ? List.of() : List.copyOf(series);
		omitidas = omitidas == null ? List.of() : List.copyOf(omitidas);
		advertencias = advertencias == null ? List.of() : List.copyOf(advertencias);
	}

	/**
	 * La serie de un indicador. {@code total} es la suma de los puntos y coincide con el valor del
	 * indicador {@code clave} de la seccion sobre el mismo periodo.
	 */
	public record Serie(
			String seccion,
			String clave,
			String etiqueta,
			IndicadorDeReporte.Tipo tipo,
			String moneda,
			String fuente,
			String criterioDeFecha,
			BigDecimal total,
			List<Punto> puntos) {

		public Serie {
			puntos = puntos == null ? List.of() : List.copyOf(puntos);
		}
	}

	/** Un punto: los dias {@code [desde, hasta]}, inclusivos, y su valor. Cero explicito si no hubo. */
	public record Punto(LocalDate desde, LocalDate hasta, BigDecimal valor) {
	}
}
