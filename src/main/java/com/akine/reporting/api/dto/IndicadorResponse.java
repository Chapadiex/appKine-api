package com.akine.reporting.api.dto;

import com.akine.reporting.spi.IndicadorDeReporte;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Un numero del reporte, con lo que hace falta para saber que significa.
 *
 * <p>{@code fuente} y {@code criterioDeFecha} viajan al cliente porque RF-M23 los pide y porque
 * sin ellos <b>el primero que vea dos numeros parecidos va a intentar sumarlos</b>. Deuda, cobro,
 * caja, presentacion y egreso son cinco conceptos distintos; sumar un cobro con un movimiento de
 * caja cuenta la misma plata dos veces.
 */
@Schema(name = "IndicadorDeReporteResponse",
		description = "Un indicador del reporte, con su fuente y su criterio de corte")
public record IndicadorResponse(
		@Schema(description = "Identificador estable", example = "cobrado")
		String clave,

		@Schema(description = "Como se lee en pantalla", example = "Cobrado")
		String etiqueta,

		@Schema(description = "DINERO, CONTEO o PORCENTAJE", example = "DINERO")
		String tipo,

		@Schema(description = "El numero. Siempre decimal exacto, nunca punto flotante")
		BigDecimal valor,

		@Schema(description = "ISO-4217 cuando el tipo es DINERO", example = "ARS")
		String moneda,

		@Schema(description = "De que modulo y tabla sale", example = "M19 cobro")
		String fuente,

		@Schema(description = "Con que columna se recorto el periodo",
				example = "cobrado_en, instante proyectado a la zona de la sede")
		String criterioDeFecha) {

	public static IndicadorResponse de(IndicadorDeReporte indicador) {
		return new IndicadorResponse(
				indicador.clave(),
				indicador.etiqueta(),
				indicador.tipo().name(),
				indicador.valor(),
				indicador.moneda(),
				indicador.fuente(),
				indicador.criterioDeFecha());
	}
}
