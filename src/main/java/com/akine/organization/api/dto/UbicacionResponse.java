package com.akine.organization.api.dto;

import com.akine.organization.application.ConsultorioView;
import com.akine.organization.domain.UbicacionOrigen;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/** Coordenadas de la sede y quien las puso. */
@Schema(description = "Coordenadas WGS84 de la sede y su origen")
public record UbicacionResponse(

		@Schema(description = "Latitud", example = "-31.420083")
		BigDecimal latitud,

		@Schema(description = "Longitud", example = "-64.188776")
		BigDecimal longitud,

		@Schema(description = "GEOCODIFICADA (la devolvio la busqueda de direcciones) o MANUAL (la "
				+ "fijo una persona)", example = "GEOCODIFICADA")
		UbicacionOrigen origen) {

	public static UbicacionResponse from(ConsultorioView.UbicacionView view) {
		return view == null ? null : new UbicacionResponse(view.latitud(), view.longitud(), view.origen());
	}
}
