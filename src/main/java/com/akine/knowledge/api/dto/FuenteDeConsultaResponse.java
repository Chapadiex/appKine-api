package com.akine.knowledge.api.dto;

import com.akine.knowledge.application.FuenteDeConsulta;
import io.swagger.v3.oas.annotations.media.Schema;

/** Un pasaje que se le paso al modelo para responder; el extracto son sus primeros 300 caracteres. */
@Schema(description = "Fuente de una respuesta de la biblioteca: libro, paginas y extracto del pasaje",
		requiredProperties = {"libroId", "titulo", "paginaDesde", "paginaHasta", "extracto"})
public record FuenteDeConsultaResponse(
		long libroId, String titulo, int paginaDesde, int paginaHasta,
		@Schema(maxLength = 300) String extracto) {

	public static FuenteDeConsultaResponse de(FuenteDeConsulta f) {
		return new FuenteDeConsultaResponse(f.libroId(), f.titulo(), f.paginaDesde(), f.paginaHasta(), f.extracto());
	}
}
