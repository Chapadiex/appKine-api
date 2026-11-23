package com.akine.assistant.api.dto;

import com.akine.assistant.spi.ResultadoDeAccion;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Como termino la confirmacion de una accion. */
@Schema(name = "ResultadoDeAccion", description = "Como termino la confirmacion de una accion del asistente")
public record ResultadoDeAccionResponse(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"CONFIRMADA", "RECHAZADA"},
				description = "RECHAZADA: no se grabo nada y la propuesta sigue abierta para corregirla")
		String estado,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Registré el cobro de $ 15.000 a Juan Pérez.")
		String texto,
		@Schema(description = "Ruta del frontend donde ver lo grabado", example = "/pacientes/123") String enlace,
		@ArraySchema(arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
				description = "Lo que no salio como se pidio (items de un lote que fallaron)"))
		List<String> detalles) {

	public static ResultadoDeAccionResponse de(ResultadoDeAccion r) {
		return new ResultadoDeAccionResponse(r.estado().name(), r.texto(), r.enlace(), r.detalles());
	}
}
