package com.akine.resource.api.dto;

import com.akine.resource.application.CatalogoConceptoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Pagina de conceptos del catalogo.
 *
 * <p>Misma forma que el resto de los listados paginados de la API, por la misma razon: el
 * cliente aprende una sola estructura de paginado y no una por endpoint.
 *
 * <p><b>Nota honesta sobre el paginado.</b> La fuente devuelve todos los conceptos que cumplen
 * el filtro y el recorte se hace en memoria. Es aceptable mientras el filtro por duenio, estado
 * y texto ya ocurrio en la base —resuelto por {@code ix_*_owner_estado} y por
 * {@code ix_practica_busqueda}— y el resultado de una busqueda incremental es chico por
 * construccion. <b>Es tambien el limite conocido de esta etapa</b>: un nomenclador nacional
 * completo son miles de codigos, y el dia que se importe uno entero el recorte tiene que bajar
 * al repositorio. Este contrato no cambia cuando eso pase.
 */
@Schema(description = "Pagina de conceptos del catalogo, segun los filtros pedidos")
public record CatalogoConceptoPageResponse(

		@Schema(description = "Conceptos de esta pagina")
		List<CatalogoConceptoResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Cantidad de elementos por pagina", example = "20")
		int size,

		@Schema(description = "Cantidad total de conceptos que cumplen el filtro", example = "37")
		long totalElements,

		@Schema(description = "Cantidad total de paginas disponibles", example = "2")
		int totalPages) {

	/**
	 * Recorta la lista completa a la pagina pedida.
	 *
	 * <p>Una pagina fuera de rango devuelve contenido vacio, no un error: pedir la pagina 10 de
	 * un listado de 3 elementos no es una solicitud invalida, es un listado que se quedo corto.
	 */
	public static CatalogoConceptoPageResponse of(
			List<CatalogoConceptoView> todos, int page, int size) {

		int desde = Math.min(page * size, todos.size());
		int hasta = Math.min(desde + size, todos.size());
		List<CatalogoConceptoResponse> contenido = todos.subList(desde, hasta).stream()
				.map(CatalogoConceptoResponse::from)
				.toList();
		int totalPaginas = (int) Math.ceil((double) todos.size() / size);
		return new CatalogoConceptoPageResponse(
				contenido, page, size, todos.size(), totalPaginas);
	}
}
