package com.akine.knowledge.api.dto;

import com.akine.knowledge.application.ConsultaPedido;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Pregunta a la biblioteca con el historial de la conversacion. Los limites los valida el servicio (400). */
@Schema(description = "Pregunta a la biblioteca. El servidor no guarda la conversacion: el historial lo manda "
		+ "el cliente en cada pregunta.")
public record ConsultaRequest(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 600) String pregunta,
		@ArraySchema(maxItems = 6, schema = @Schema(implementation = TurnoDeConsultaRequest.class),
				arraySchema = @Schema(description = "Turnos previos, del mas viejo al mas nuevo; hasta 6"))
		List<TurnoDeConsultaRequest> historial,
		@ArraySchema(maxItems = 10, schema = @Schema(type = "integer", format = "int64", minimum = "1"),
				arraySchema = @Schema(description = "Libros de las fuentes de la respuesta anterior, solo en una "
						+ "repregunta o al profundizar. Solo reordenan los resultados dentro de la organizacion, "
						+ "y solo si la consulta continua la charla: una que nombra su propio tema los ignora"))
		List<Long> librosPreferidos) {

	public ConsultaPedido aPedido() {
		return new ConsultaPedido(pregunta,
				historial == null ? null : historial.stream()
						.map(t -> t == null ? null : t.aTurno())
						.toList(),
				librosPreferidos);
	}
}
