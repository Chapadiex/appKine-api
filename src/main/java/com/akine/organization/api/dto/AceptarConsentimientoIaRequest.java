package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Aceptacion del consentimiento de IA en nombre del centro")
public record AceptarConsentimientoIaRequest(

		@Schema(description = "Version del texto que se leyo y se acepta; tiene que ser la vigente",
				example = "2026-11-26", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "La version del consentimiento es obligatoria")
		@Size(max = 32, message = "La version del consentimiento no puede superar los 32 caracteres")
		String version) {
}
