package com.akine.person.api.dto;

import com.akine.person.application.AdjuntoService.AdjuntoPagina;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Pagina de adjuntos de una persona. Misma forma que {@link PersonaPageResponse}. */
@Schema(description = "Pagina de adjuntos administrativos de una persona")
public record AdjuntoPageResponse(

		@Schema(description = "Adjuntos de esta pagina, mas nuevos primero")
		List<AdjuntoResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Cantidad de elementos por pagina", example = "20")
		int size,

		@Schema(description = "Cantidad total de adjuntos que cumplen el filtro", example = "4")
		long totalElements,

		@Schema(description = "Cantidad total de paginas disponibles", example = "1")
		int totalPages) {

	public static AdjuntoPageResponse of(AdjuntoPagina pagina, int page, int size) {
		List<AdjuntoResponse> contenido = pagina.contenido().stream()
				.map(AdjuntoResponse::from)
				.toList();
		int totalPaginas = (int) Math.ceil((double) pagina.total() / size);
		return new AdjuntoPageResponse(contenido, page, size, pagina.total(), totalPaginas);
	}
}
