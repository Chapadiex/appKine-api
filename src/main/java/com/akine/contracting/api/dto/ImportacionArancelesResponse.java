package com.akine.contracting.api.dto;

import com.akine.contracting.application.ImportacionAranceles.Resultado;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** El resultado de una importacion de aranceles: preview o confirmacion aplicada (B-7). */
@Schema(description = "Resultado de una importacion de aranceles")
public record ImportacionArancelesResponse(

		ModoImportacionAranceles modo,

		@Schema(description = "true solo en una confirmacion que inserto el lote. Un preview nunca "
				+ "aplica, y una confirmacion rechazada responde 409")
		boolean aplicada,

		@Schema(description = "Filas del lote", example = "120")
		int totalFilas,

		@Schema(description = "Filas que entran (o entraron) como arancel nuevo", example = "118")
		long filasConAlta,

		@Schema(description = "Filas que no entrarian", example = "2")
		long filasRechazadas,

		@Schema(description = "El desenlace de cada fila, en el orden del lote")
		List<FilaImportacionArancelResponse> filas) {

	public static ImportacionArancelesResponse de(Resultado resultado) {
		return new ImportacionArancelesResponse(
				ModoImportacionAranceles.valueOf(resultado.modo().name()),
				resultado.aplicada(),
				resultado.filas().size(),
				resultado.filasConAlta(),
				resultado.filasRechazadas(),
				resultado.filas().stream().map(FilaImportacionArancelResponse::de).toList());
	}
}
