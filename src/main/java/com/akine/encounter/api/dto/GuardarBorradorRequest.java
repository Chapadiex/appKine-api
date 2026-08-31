package com.akine.encounter.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * El borrador que se guarda, mas la version que el cliente leyo.
 *
 * @param contenido JSON opaco. Esta etapa no valida su forma: que campos tiene una evaluacion es
 *                  asunto de AKINE-06.02, y darle esquema hoy fijaria en la base un formulario que
 *                  todavia no esta decidido
 */
@Schema(
		name = "GuardarBorrador",
		description = "Guardado parcial de la atencion. La version es obligatoria: sin ella dos "
				+ "pestanas del mismo profesional se pisan en silencio.")
public record GuardarBorradorRequest(

		@Schema(
				description = "Contenido parcial de la atencion, como JSON. Opaco para el servidor.",
				example = "{\"motivoConsulta\":\"dolor lumbar\",\"observaciones\":\"\"}")
		@Size(max = 65_000) String contenido,

		@Schema(
				description = "Version que el cliente leyo. Un 409 `concurrent-modification` "
						+ "significa que alguien guardo antes y hay que recargar.",
				example = "3")
		@NotNull @PositiveOrZero Long version) {
}
