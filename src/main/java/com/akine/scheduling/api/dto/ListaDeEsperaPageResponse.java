package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.EntradasDeEsperaPagina;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "ListaDeEsperaPage")
public record ListaDeEsperaPageResponse(
		List<EntradaDeEsperaResponse> content,
		@Schema(example = "0") int page,
		@Schema(example = "20") int size,
		@Schema(example = "3") long totalElements) {

	public static ListaDeEsperaPageResponse de(EntradasDeEsperaPagina pagina, int page, int size) {
		return new ListaDeEsperaPageResponse(
				pagina.entradas().stream().map(EntradaDeEsperaResponse::de).toList(), page, size, pagina.total());
	}
}
