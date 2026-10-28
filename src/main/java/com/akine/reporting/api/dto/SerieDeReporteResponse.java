package com.akine.reporting.api.dto;

import com.akine.reporting.application.SerieDeReporteView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Las series temporales de un reporte, para el grafico de barras (DP-45).
 *
 * <p>Cada serie dibuja <b>un</b> indicador del reporte —{@code clave} es la del indicador— con su
 * fuente y su criterio de fecha. Nunca hay una serie que sume dos conceptos economicos: deuda,
 * cobro, caja y egreso tienen cada uno la suya.
 */
@Schema(name = "SerieDeReporte",
		description = "Series por dia o por semana de los indicadores de un reporte, en la zona de la "
				+ "sede, con las mismas omisiones por permiso que el reporte")
public record SerieDeReporteResponse(
		@Schema(example = "ECONOMICO") String reporte,
		long consultorioId,
		@Schema(description = "Primer dia del periodo, inclusive, en la zona de la sede") LocalDate desde,
		@Schema(description = "Ultimo dia del periodo, inclusive, en la zona de la sede") LocalDate hasta,
		@Schema(example = "America/Argentina/Cordoba") String zona,
		@Schema(allowableValues = {"DIA", "SEMANA"}, example = "DIA") String agrupacion,
		Instant generadoEn,
		List<SerieResponse> series,
		@Schema(description = "Secciones que el actor no puede ver: no tienen serie y no producen 403")
		List<ReporteResponse.SeccionOmitidaResponse> omitidas,
		@Schema(description = "Aclaraciones de alcance, por ejemplo `alcance-actividad-propia`")
		List<ReporteResponse.AdvertenciaResponse> advertencias) {

	@Schema(name = "SerieDeIndicadorDeReporte",
			description = "La serie de un indicador. La suma de los puntos es `total`, y coincide con el "
					+ "valor del indicador `clave` de la seccion sobre el mismo periodo")
	public record SerieResponse(
			@Schema(example = "economia") String seccion,
			@Schema(description = "La clave del indicador que dibuja", example = "cobrado") String clave,
			@Schema(example = "Cobrado") String etiqueta,
			@Schema(allowableValues = {"DINERO", "CONTEO"}, example = "DINERO") String tipo,
			@Schema(description = "ISO-4217 cuando el tipo es DINERO", example = "ARS") String moneda,
			@Schema(example = "M19 cobro") String fuente,
			@Schema(example = "cobrado_en, instante proyectado a la zona de la sede") String criterioDeFecha,
			@Schema(description = "Suma de los puntos. Decimal exacto", example = "125000.00") BigDecimal total,
			List<PuntoResponse> puntos) {
	}

	@Schema(name = "PuntoDeSerieDeReporte",
			description = "Una barra: los dias `desde`..`hasta` inclusive y su valor. Cero explicito si "
					+ "no hubo actividad. Una semana se recorta al periodo pedido")
	public record PuntoResponse(LocalDate desde, LocalDate hasta, BigDecimal valor) {
	}

	public static SerieDeReporteResponse de(SerieDeReporteView vista) {
		return new SerieDeReporteResponse(
				vista.reporte().name(),
				vista.consultorioId(),
				vista.desde(),
				vista.hasta(),
				vista.zona(),
				vista.agrupacion().name(),
				vista.generadoEn(),
				vista.series().stream()
						.map(s -> new SerieResponse(s.seccion(), s.clave(), s.etiqueta(), s.tipo().name(),
								s.moneda(), s.fuente(), s.criterioDeFecha(), s.total(),
								s.puntos().stream()
										.map(p -> new PuntoResponse(p.desde(), p.hasta(), p.valor()))
										.toList()))
						.toList(),
				vista.omitidas().stream()
						.map(o -> new ReporteResponse.SeccionOmitidaResponse(o.seccion(), o.permisoRequerido()))
						.toList(),
				vista.advertencias().stream()
						.map(a -> new ReporteResponse.AdvertenciaResponse(a.seccion(), a.codigo(), a.detalle()))
						.toList());
	}
}
