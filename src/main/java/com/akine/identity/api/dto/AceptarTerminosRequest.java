package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Aceptacion de la version vigente de los terminos por una cuenta ya creada")
public record AceptarTerminosRequest(
		@Schema(description = "La versionVigente de GET /api/v1/me/terminos. Tiene que coincidir exacta",
				example = "2026-11-19")
		@NotBlank(message = "Indicá la versión de los términos que aceptás")
		@Size(max = 40, message = "La versión de los términos admite hasta 40 caracteres")
		String versionTerminos) {
}
