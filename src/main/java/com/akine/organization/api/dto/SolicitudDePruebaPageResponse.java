package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Pagina de pedidos de prueba gratis")
public record SolicitudDePruebaPageResponse(
		@Schema(description = "Pedidos de esta pagina, los mas nuevos primero")
		List<SolicitudDePruebaResponse> content,
		@Schema(description = "Numero de pagina, base cero", example = "0")
		int page,
		@Schema(description = "Elementos por pagina", example = "10")
		int size,
		@Schema(description = "Total que cumple el filtro", example = "4")
		long totalElements,
		@Schema(description = "Total de paginas", example = "1")
		int totalPages,
		@Schema(description = "Pedidos pendientes en total, sin filtro: el numero de la bandeja", example = "2")
		long pendientes) {
}
