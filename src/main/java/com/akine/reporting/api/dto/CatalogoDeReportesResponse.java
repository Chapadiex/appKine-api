package com.akine.reporting.api.dto;

import com.akine.reporting.application.ReporteService;
import com.akine.reporting.spi.ReporteCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Que reportes existen y que secciones trae cada uno.
 *
 * <p>Existe para que la pantalla pueda dibujar el menu sin ejecutar cinco reportes, y para que
 * pueda explicar de antemano por que una seccion no va a aparecer.
 */
@Schema(description = "Catalogo de reportes disponibles y sus secciones")
public record CatalogoDeReportesResponse(List<ReporteDisponible> reportes) {

	public static CatalogoDeReportesResponse de(
			Map<ReporteCode, List<ReporteService.SeccionDisponible>> catalogo) {

		return new CatalogoDeReportesResponse(catalogo.entrySet().stream()
				.map(entrada -> new ReporteDisponible(
						entrada.getKey().name(),
						entrada.getValue().stream()
								.map(s -> new SeccionDisponibleResponse(
										s.seccion(), s.titulo(), s.permisoRequerido(), s.clinica()))
								.toList()))
				.toList());
	}

	@Schema(description = "Un reporte y sus secciones")
	public record ReporteDisponible(String reporte, List<SeccionDisponibleResponse> secciones) {
	}

	@Schema(description = "Una seccion, el permiso que pide y si toca datos clinicos")
	public record SeccionDisponibleResponse(
			String seccion,
			String titulo,
			@Schema(description = "null cuando alcanza con pertenecer al tenant")
			String permisoRequerido,
			@Schema(description = "Si es true, consultarla queda auditada")
			boolean clinica) {
	}
}
