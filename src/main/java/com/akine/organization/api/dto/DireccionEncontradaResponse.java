package com.akine.organization.api.dto;

import com.akine.organization.application.Geocodificador;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/** Un candidato de la busqueda de direcciones. Las partes pueden faltar; etiqueta y coordenadas no. */
@Schema(description = "Direccion candidata devuelta por el geocodificador")
public record DireccionEncontradaResponse(

		@Schema(description = "La direccion completa tal como la describe el proveedor, para mostrar en la lista",
				example = "Avenida Colon 1234, Centro, Cordoba, Argentina")
		String etiqueta,

		@Schema(description = "Latitud", example = "-31.413500")
		BigDecimal latitud,

		@Schema(description = "Longitud", example = "-64.192300")
		BigDecimal longitud,

		@Schema(description = "Calle", example = "Avenida Colon")
		String calle,

		@Schema(description = "Altura", example = "1234")
		String numero,

		@Schema(description = "Ciudad o localidad", example = "Cordoba")
		String ciudad,

		@Schema(description = "Provincia o estado", example = "Cordoba")
		String provincia,

		@Schema(description = "Codigo postal", example = "X5000")
		String codigoPostal,

		@Schema(description = "Pais, ISO 3166-1 alpha-2 en mayusculas", example = "AR")
		String pais) {

	public static DireccionEncontradaResponse from(Geocodificador.DireccionEncontrada direccion) {
		return new DireccionEncontradaResponse(
				direccion.etiqueta(), direccion.latitud(), direccion.longitud(), direccion.calle(),
				direccion.numero(), direccion.ciudad(), direccion.provincia(), direccion.codigoPostal(),
				direccion.pais());
	}
}
