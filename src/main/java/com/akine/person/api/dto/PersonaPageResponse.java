package com.akine.person.api.dto;

import com.akine.person.application.PersonaPagina;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Pagina del padron de personas.
 *
 * <p>Misma forma que el resto de los listados paginados de la API, para que el cliente aprenda
 * una sola estructura. La diferencia con {@code EspacioPageResponse} y con
 * {@code CatalogoConceptoPageResponse} <b>no se ve desde afuera pero importa</b>: aquellos traen
 * la coleccion completa y la recortan en memoria, y este recorta en la base. El padron es la
 * primera tabla del sistema con volumen real —decenas de miles de filas contra las decenas de
 * espacios de una sede— y RNF-M07-004 pide tiempos compatibles con eso.
 */
@Schema(description = "Pagina de personas del padron, segun los filtros pedidos")
public record PersonaPageResponse(

		@Schema(description = "Personas de esta pagina")
		List<PersonaResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Cantidad de elementos por pagina", example = "20")
		int size,

		@Schema(description = "Cantidad total de personas que cumplen el filtro", example = "134")
		long totalElements,

		@Schema(description = "Cantidad total de paginas disponibles", example = "7")
		int totalPages) {

	public static PersonaPageResponse of(PersonaPagina pagina, int page, int size) {
		List<PersonaResponse> contenido = pagina.contenido().stream()
				.map(PersonaResponse::from)
				.toList();
		int totalPaginas = (int) Math.ceil((double) pagina.total() / size);
		return new PersonaPageResponse(contenido, page, size, pagina.total(), totalPaginas);
	}
}
