package com.akine.assistant.api.dto;

import com.akine.assistant.application.TurnoPrevio;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Una pregunta anterior de la conversacion y su interpretacion. */
@Schema(description = "Una pregunta anterior de la conversacion y la interpretacion que devolvio el servidor")
public record TurnoPrevioRequest(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 300)
		@NotBlank(message = "La pregunta del historial es obligatoria")
		@Size(max = 300, message = "La pregunta del historial admite hasta 300 caracteres") String pregunta,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull(message = "La interpretacion del historial es obligatoria") @Valid InterpretacionDto interpretacion) {

	public TurnoPrevio aTurno() {
		return new TurnoPrevio(pregunta, interpretacion.aInterpretacion());
	}
}
