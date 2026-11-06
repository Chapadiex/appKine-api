package com.akine.organization.api.dto;

import com.akine.organization.application.DireccionesService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Resultado de la busqueda de direcciones.
 *
 * <p>{@code atribucion} no es decorativa: la licencia del proveedor (OpenStreetMap hoy) exige
 * mostrarla junto a los resultados, y la pantalla la toma de aca para no fijarla en el cliente.
 */
@Schema(description = "Candidatos de la busqueda de direcciones, con el proveedor y su atribucion")
public record BusquedaDeDireccionesResponse(

		@Schema(description = "Proveedor que respondio", example = "NOMINATIM")
		String proveedor,

		@Schema(description = "Texto de atribucion que hay que mostrar junto a los resultados",
				example = "(c) OpenStreetMap contributors")
		String atribucion,

		@Schema(description = "Hasta cinco candidatos, los mejores primero. Vacio si no hay ninguno")
		List<DireccionEncontradaResponse> resultados) {

	public static BusquedaDeDireccionesResponse from(DireccionesService.BusquedaDeDirecciones busqueda) {
		return new BusquedaDeDireccionesResponse(
				busqueda.proveedor(),
				busqueda.atribucion(),
				busqueda.resultados().stream().map(DireccionEncontradaResponse::from).toList());
	}
}
