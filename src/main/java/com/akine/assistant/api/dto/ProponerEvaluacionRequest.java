package com.akine.assistant.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "ProponerEvaluacionDictada")
public record ProponerEvaluacionRequest(
		@Schema(description = "El texto dictado, normalmente el que devolvio la transcripcion",
				requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 4000,
				example = "Dolor 6 en rodilla derecha, mejor que la vez pasada. Objetivo: ganar flexion.")
		@NotBlank @Size(max = 4000) String texto) {
}
