package com.akine.reporting.api.dto;

import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.FilaDeReporte;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Un bloque del reporte, aportado por el modulo propietario de sus datos. */
@Schema(description = "Una seccion del reporte")
public record SeccionResponse(
		@Schema(description = "Nombre estable de la seccion", example = "economia")
		String seccion,

		@Schema(description = "Titulo legible", example = "Economia")
		String titulo,

		List<IndicadorResponse> indicadores,

		@Schema(description = "Encabezados del detalle; vacio si la seccion no tiene detalle")
		List<String> columnas,

		@Schema(description = "Detalle. Ninguna fila lleva identificadores de persona")
		List<List<String>> filas) {

	public static SeccionResponse de(AporteDeReporte aporte) {
		return new SeccionResponse(
				aporte.seccion(),
				aporte.titulo(),
				aporte.indicadores().stream().map(IndicadorResponse::de).toList(),
				aporte.columnas(),
				aporte.filas().stream().map(FilaDeReporte::celdas).toList());
	}
}
