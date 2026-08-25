package com.akine.resource.api.dto;

import com.akine.resource.application.EspacioView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Pagina de espacios de una sede.
 *
 * <p>Misma forma que el resto de los listados paginados de la API, por la misma razon: el
 * cliente aprende una sola estructura de paginado y no una por endpoint.
 *
 * <p><b>Nota honesta sobre el paginado.</b> La fuente devuelve todos los espacios de la sede que
 * cumplen el filtro y el recorte se hace en memoria. Es aceptable porque la cantidad de espacios
 * de una sede esta acotada por su tamaño fisico —decenas como maximo, no miles— y porque el
 * filtro por tenant, sede y estado ya ocurrio en la base, resuelto por
 * {@code ix_espacio_sede_estado}. El dia que un caso real lo desmienta, el recorte baja al
 * repositorio sin cambiar este contrato.
 */
@Schema(description = "Pagina de espacios de la sede, segun el filtro de estado pedido")
public record EspacioPageResponse(

		@Schema(description = "Espacios de esta pagina")
		List<EspacioResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Cantidad de elementos por pagina", example = "20")
		int size,

		@Schema(description = "Cantidad total de espacios que cumplen el filtro", example = "4")
		long totalElements,

		@Schema(description = "Cantidad total de paginas disponibles", example = "1")
		int totalPages) {

	/**
	 * Recorta la lista completa a la pagina pedida.
	 *
	 * <p>Una pagina fuera de rango devuelve contenido vacio, no un error: pedir la pagina 10 de
	 * un listado de 3 elementos no es una solicitud invalida, es un listado que se quedo corto.
	 */
	public static EspacioPageResponse of(List<EspacioView> todos, int page, int size) {
		int desde = Math.min(page * size, todos.size());
		int hasta = Math.min(desde + size, todos.size());
		List<EspacioResponse> contenido = todos.subList(desde, hasta).stream()
				.map(EspacioResponse::from)
				.toList();
		int totalPaginas = (int) Math.ceil((double) todos.size() / size);
		return new EspacioPageResponse(contenido, page, size, todos.size(), totalPaginas);
	}
}
