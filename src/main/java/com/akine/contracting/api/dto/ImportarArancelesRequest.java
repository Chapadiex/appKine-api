package com.akine.contracting.api.dto;

import com.akine.contracting.application.ImportacionArancelesService;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Importacion masiva de aranceles de un convenio (B-7, RF-M16-007). */
@Schema(description = "Lote de aranceles a previsualizar o confirmar")
public record ImportarArancelesRequest(

		@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La importacion necesita un modo: PREVIEW o CONFIRMAR")
		ModoImportacionAranceles modo,

		@ArraySchema(
				arraySchema = @Schema(description = "Las filas, en el orden de la planilla. La "
						+ "posicion (desde 1) es la que identifica cada fila en la respuesta",
						requiredMode = Schema.RequiredMode.REQUIRED),
				minItems = 1,
				maxItems = ImportacionArancelesService.MAX_FILAS)
		@NotEmpty(message = "La importacion necesita al menos una fila")
		@Size(max = ImportacionArancelesService.MAX_FILAS,
				message = "La importacion admite hasta 500 filas por lote")
		List<FilaImportacionArancelRequest> filas) {
}
