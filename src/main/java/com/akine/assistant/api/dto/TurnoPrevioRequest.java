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

/** Una pregunta anterior de la conversacion, el texto que respondio el servidor y las personas nombradas. */
@Schema(description = "Una pregunta anterior de la conversacion, el texto que respondio el servidor y los ids de "
		+ "las personas que nombro. Nunca resultados de herramientas: el servidor vuelve a pedir cada dato")
public record TurnoPrevioRequest(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 300)
		@NotBlank(message = "La pregunta del historial es obligatoria")
		@Size(max = 300, message = "La pregunta del historial admite hasta 300 caracteres") String pregunta,
		@Schema(deprecated = true, description = "Deprecada desde el agente: se acepta y se ignora")
		@Deprecated @Valid InterpretacionDto interpretacion,
		@Schema(description = "El texto que respondio el servidor", maxLength = 2000)
		@Size(max = 2000, message = "La respuesta del historial admite hasta 2000 caracteres") String respuesta,
		@ArraySchema(maxItems = 10, arraySchema = @Schema(description = "Ids de las personas que nombro esa "
				+ "respuesta; el servidor los valida contra el tenant"),
				schema = @Schema(type = "integer", format = "int64", minimum = "1"))
		@Size(max = 10, message = "El historial admite hasta 10 personas por turno")
		List<@NotNull(message = "Las personas del historial no admiten elementos vacios")
				@Positive(message = "Las personas del historial tienen que ser ids positivos") Long> personaIds) {

	public TurnoPrevio aTurno() {
		return new TurnoPrevio(pregunta, respuesta, personaIds);
	}
}
