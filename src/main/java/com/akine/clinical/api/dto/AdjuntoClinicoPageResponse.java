package com.akine.clinical.api.dto;

import com.akine.clinical.application.AdjuntoClinicoService.AdjuntoClinicoPagina;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Pagina de adjuntos clinicos de una historia.
 *
 * <p>Si lleva total y cantidad de paginas, a diferencia de {@link TimelineResponse}: una sola
 * consulta sobre una sola tabla puede contar barato, y el listado de documentos de un paciente
 * es finito y se recorre con paginas numeradas. El timeline no es ninguna de las dos cosas.
 */
@Schema(description = "Pagina de documentos clinicos de una Historia Clinica")
public record AdjuntoClinicoPageResponse(

		@Schema(description = "Adjuntos de esta pagina, mas nuevos primero")
		List<AdjuntoClinicoResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Cantidad de elementos por pagina", example = "20")
		int size,

		@Schema(description = "Cantidad total de adjuntos que cumplen el filtro", example = "4")
		long totalElements,

		@Schema(description = "Cantidad total de paginas disponibles", example = "1")
		int totalPages) {

	public static AdjuntoClinicoPageResponse of(AdjuntoClinicoPagina pagina, int page, int size) {
		List<AdjuntoClinicoResponse> contenido = pagina.contenido().stream()
				.map(AdjuntoClinicoResponse::from)
				.toList();
		int totalPaginas = (int) Math.ceil((double) pagina.total() / size);
		return new AdjuntoClinicoPageResponse(
				contenido, page, size, pagina.total(), totalPaginas);
	}
}
