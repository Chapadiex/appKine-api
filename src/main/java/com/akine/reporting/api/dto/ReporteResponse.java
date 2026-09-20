package com.akine.reporting.api.dto;

import com.akine.reporting.application.ReporteView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Un reporte resuelto.
 *
 * <p><b>{@code omitidas} y {@code advertencias} son el corazon de este contrato</b>, y las dos
 * existen por la misma razon: un cero sin explicacion es una mentira que el operador no puede
 * detectar.
 */
@Schema(description = "Un reporte del MVP, con lo que muestra y lo que no puede mostrar")
public record ReporteResponse(
		@Schema(description = "Que reporte es", example = "ECONOMICO")
		String reporte,

		long consultorioId,

		@Schema(description = "Primer dia del periodo, inclusive, en la zona de la sede")
		LocalDate desde,

		@Schema(description = "Ultimo dia del periodo, inclusive, en la zona de la sede")
		LocalDate hasta,

		@Schema(description = "Zona IANA con la que se recorto el periodo",
				example = "America/Argentina/Cordoba")
		String zona,

		@Schema(description = "Instante de la corrida. Un periodo abierto cambia entre consultas")
		Instant generadoEn,

		List<SeccionResponse> secciones,

		@Schema(description = "Secciones que el actor no puede ver, con el permiso que le falta. "
				+ "No producen 403: el reporte devuelve lo que se puede ver y declara el resto")
		List<SeccionOmitidaResponse> omitidas,

		@Schema(description = "Indicadores que valen cero por construccion y no por falta de "
				+ "actividad")
		List<AdvertenciaResponse> advertencias) {

	public static ReporteResponse de(ReporteView vista) {
		return new ReporteResponse(
				vista.reporte().name(),
				vista.consultorioId(),
				vista.desde(),
				vista.hasta(),
				vista.zona(),
				vista.generadoEn(),
				vista.secciones().stream().map(SeccionResponse::de).toList(),
				vista.omitidas().stream()
						.map(o -> new SeccionOmitidaResponse(o.seccion(), o.permisoRequerido()))
						.toList(),
				vista.advertencias().stream()
						.map(a -> new AdvertenciaResponse(a.seccion(), a.codigo(), a.detalle()))
						.toList());
	}

	/** Viaja el codigo de permiso y no una frase: la pantalla decide como decirlo. */
	@Schema(description = "Una seccion omitida y el permiso que falta para verla")
	public record SeccionOmitidaResponse(String seccion, String permisoRequerido) {
	}

	@Schema(description = "Una aclaracion sobre los numeros de una seccion")
	public record AdvertenciaResponse(
			String seccion,
			@Schema(example = "sin-devengado-de-financiador") String codigo,
			String detalle) {
	}
}
