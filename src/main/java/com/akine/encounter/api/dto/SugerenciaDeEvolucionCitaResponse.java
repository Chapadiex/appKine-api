package com.akine.encounter.api.dto;

import com.akine.encounter.spi.CitaGuardada;
import io.swagger.v3.oas.annotations.media.Schema;

/** Un pasaje de la biblioteca que respalda una sugerencia de evolucion. */
@Schema(description = "Pasaje de la biblioteca citado por una sugerencia de evolucion")
public record SugerenciaDeEvolucionCitaResponse(

		@Schema(description = "Libro de la biblioteca", example = "12")
		long libroId,

		@Schema(description = "Titulo del libro")
		String titulo,

		@Schema(description = "Primera pagina del pasaje", example = "41")
		int paginaDesde,

		@Schema(description = "Ultima pagina del pasaje", example = "42")
		int paginaHasta,

		@Schema(description = "Similitud con la consulta, entre 0 y 1", example = "0.83")
		double puntaje,

		@Schema(description = "SHA-256 del texto del pasaje: permite saber despues si cambio")
		String sha256) {

	public static SugerenciaDeEvolucionCitaResponse de(CitaGuardada cita) {
		return new SugerenciaDeEvolucionCitaResponse(cita.libroId(), cita.titulo(), cita.paginaDesde(),
				cita.paginaHasta(), cita.puntaje(), cita.sha256());
	}
}
