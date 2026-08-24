package com.akine.organization.api.dto;

import com.akine.organization.application.ConsultorioView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Pagina de sedes del tenant.
 *
 * <p>Misma forma que el resto de los listados paginados de la API, por la misma razon: el
 * cliente aprende una sola estructura de paginado y no una por endpoint.
 *
 * <p><b>Nota honesta sobre el paginado.</b> En 01.01 la fuente de datos devuelve todas las
 * sedes activas del tenant y el recorte se hace en memoria. Es aceptable porque la cantidad de
 * sedes por centro esta acotada por el limite del plan —decenas como maximo, no miles— y
 * porque el filtro por tenant ya ocurrio en la base. Cuando 02.01 traiga la administracion
 * completa de consultorios, el recorte baja al repositorio sin cambiar este contrato.
 */
@Schema(description = "Pagina de sedes de la organizacion, segun el filtro de estado pedido")
public record ConsultorioPageResponse(

		@Schema(description = "Sedes de esta pagina")
		List<ConsultorioResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Cantidad de elementos por pagina", example = "20")
		int size,

		@Schema(description = "Cantidad total de sedes que cumplen el filtro", example = "3")
		long totalElements,

		@Schema(description = "Cantidad total de paginas disponibles", example = "1")
		int totalPages) {

	/**
	 * Recorta la lista completa a la pagina pedida.
	 *
	 * <p>Una pagina fuera de rango devuelve contenido vacio, no un error: pedir la pagina 10 de
	 * un listado de 3 elementos no es una solicitud invalida, es un listado que se quedo corto.
	 */
	public static ConsultorioPageResponse of(List<ConsultorioView> todas, int page, int size) {
		int desde = Math.min(page * size, todas.size());
		int hasta = Math.min(desde + size, todas.size());
		List<ConsultorioResponse> contenido = todas.subList(desde, hasta).stream()
				.map(ConsultorioResponse::from)
				.toList();
		int totalPaginas = (int) Math.ceil((double) todas.size() / size);
		return new ConsultorioPageResponse(contenido, page, size, todas.size(), totalPaginas);
	}
}
