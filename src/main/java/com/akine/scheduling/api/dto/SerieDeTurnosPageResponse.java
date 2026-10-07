package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.SeriePagina;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Pagina de la bandeja de series (AKINE E-8). Misma forma que el resto de los listados paginados
 * de la API —{@code content}, {@code page}, {@code size}, {@code totalElements},
 * {@code totalPages}—, recortada en la base.
 */
@Schema(name = "SerieDeTurnosPage", description = "Pagina de series de turnos de una sede, segun los filtros pedidos")
public record SerieDeTurnosPageResponse(

		@Schema(description = "Series de esta pagina, mas nuevas primero")
		List<SerieDeTurnosResumenResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Cantidad de elementos por pagina", example = "20")
		int size,

		@Schema(description = "Cantidad total de series que cumplen el filtro", example = "34")
		long totalElements,

		@Schema(description = "Cantidad total de paginas disponibles", example = "2")
		int totalPages) {

	public static SerieDeTurnosPageResponse of(SeriePagina pagina, int page, int size) {
		return new SerieDeTurnosPageResponse(
				pagina.contenido().stream().map(SerieDeTurnosResumenResponse::de).toList(),
				page, size, pagina.total(), (int) Math.ceil((double) pagina.total() / size));
	}
}
