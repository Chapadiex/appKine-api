package com.akine.organization.api.dto;

import com.akine.organization.domain.UbicacionOrigen;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * Coordenadas de la sede, tal como se mandan al crearla o editarla.
 *
 * <p>Las dos juntas o ninguna. En el PATCH, omitir el objeto deja la ubicacion como esta;
 * mandarlo con las dos coordenadas en {@code null} la borra; mandar una sola es 400 con el campo
 * {@code ubicacion} en {@code errors}.
 */
@Schema(description = "Coordenadas WGS84 de la sede. Las dos juntas: con ambas en null se borra la "
		+ "ubicacion; omitir el objeto entero la deja como esta")
public record UbicacionRequest(

		@Schema(description = "Latitud, entre -90 y 90. En un PATCH, latitud y longitud en null borran el punto", example = "-31.420083", nullable = true,
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@DecimalMin(value = "-90", message = "La latitud tiene que estar entre -90 y 90")
		@DecimalMax(value = "90", message = "La latitud tiene que estar entre -90 y 90")
		BigDecimal latitud,

		@Schema(description = "Longitud, entre -180 y 180", example = "-64.188776", nullable = true,
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@DecimalMin(value = "-180", message = "La longitud tiene que estar entre -180 y 180")
		@DecimalMax(value = "180", message = "La longitud tiene que estar entre -180 y 180")
		BigDecimal longitud,

		@Schema(description = "Quien puso las coordenadas: GEOCODIFICADA si vienen de la busqueda de "
				+ "direcciones, MANUAL si la persona movio el marcador. Si se omite, MANUAL",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		UbicacionOrigen origen) {
}
