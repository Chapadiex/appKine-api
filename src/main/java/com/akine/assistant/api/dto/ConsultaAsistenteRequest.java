package com.akine.assistant.api.dto;

import com.akine.assistant.application.TurnoPrevio;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Una pregunta al asistente de datos. */
@Schema(description = "Una pregunta al asistente de datos. El servidor no guarda la conversacion: el historial "
		+ "lo manda el cliente en cada pregunta.")
public record ConsultaAsistenteRequest(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 600,
				example = "¿Juan vino esta semana?")
		@NotBlank(message = "La pregunta es obligatoria")
		@Size(max = 600, message = "La pregunta admite hasta 600 caracteres") String pregunta,
		@ArraySchema(maxItems = 10, schema = @Schema(implementation = TurnoPrevioRequest.class),
				arraySchema = @Schema(description = "Turnos previos, del mas viejo al mas nuevo; el servidor usa "
						+ "los ultimos tres"))
		@Size(max = 10, message = "El historial admite hasta 10 preguntas anteriores")
		List<@NotNull(message = "El historial no admite elementos vacios") @Valid TurnoPrevioRequest> historial,
		@Schema(description = "La persona elegida entre las opciones de una aclaracion. Se reenvia la misma "
				+ "pregunta con este campo", minimum = "1")
		@Positive(message = "La persona elegida tiene que ser un id positivo") Long personaId,
		@Schema(description = "Ruta del frontend desde la que se pregunta. Las preguntas sobre como usar AKINE "
				+ "prefieren la ficha del manual de esa pantalla", maxLength = 300, example = "/caja")
		@Size(max = 300, message = "La ruta admite hasta 300 caracteres") String rutaActual) {

	public List<TurnoPrevio> aHistorial() {
		return historial == null ? List.of() : historial.stream().map(TurnoPrevioRequest::aTurno).toList();
	}
}
