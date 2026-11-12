package com.akine.knowledge.api.dto;

import com.akine.knowledge.spi.CaminoBusqueda;
import com.akine.knowledge.spi.ResultadoBusqueda;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Resultado de una busqueda en la biblioteca. */
@Schema(description = "Resultado de una busqueda en la biblioteca")
public record BibliotecaBusquedaResponse(
		@Schema(description = "VECTOR por similitud; PALABRAS si el modelo no esta disponible")
		CaminoBusqueda camino,
		@Schema(description = "Puntaje minimo del camino usado") double corte,
		@Schema(description = "Hay al menos 2 pasajes sobre el corte") boolean alcanza,
		List<BibliotecaPasajeResponse> pasajes) {

	public static BibliotecaBusquedaResponse de(ResultadoBusqueda r) {
		return new BibliotecaBusquedaResponse(r.camino(), r.corte(), r.alcanza(),
				r.pasajes().stream().map(BibliotecaPasajeResponse::de).toList());
	}
}
