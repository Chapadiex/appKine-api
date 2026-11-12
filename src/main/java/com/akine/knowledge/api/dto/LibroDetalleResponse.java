package com.akine.knowledge.api.dto;

import com.akine.knowledge.application.LibroService.LibroDetalle;
import com.akine.knowledge.application.LibroService.SospechosoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Detalle de un libro: datos, fragmentos y los marcados como sospechosos")
public record LibroDetalleResponse(
		LibroResponse libro,
		@Schema(description = "Cantidad de fragmentos") long fragmentos,
		@Schema(description = "Fragmentos que ya tienen vector") long fragmentosConVector,
		@Schema(description = "Fragmentos que parecen texto corrupto o instrucciones")
		List<LibroFragmentoSospechosoResponse> sospechosos) {

	@Schema(description = "Un fragmento marcado como sospechoso")
	public record LibroFragmentoSospechosoResponse(
			@Schema(description = "Primera pagina del fragmento") int paginaDesde,
			@Schema(description = "Primeros 200 caracteres") String extracto) {
	}

	public static LibroDetalleResponse de(LibroDetalle d) {
		return new LibroDetalleResponse(
				LibroResponse.de(d.libro()), d.fragmentos(), d.fragmentosConVector(),
				d.sospechosos().stream().map(LibroDetalleResponse::sospechoso).toList());
	}

	private static LibroFragmentoSospechosoResponse sospechoso(SospechosoView s) {
		return new LibroFragmentoSospechosoResponse(s.paginaDesde(), s.extracto());
	}
}
