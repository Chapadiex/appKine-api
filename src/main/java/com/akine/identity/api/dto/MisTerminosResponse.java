package com.akine.identity.api.dto;

import com.akine.identity.application.TerminosService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Terminos vigentes para la cuenta autenticada, y si los tiene pendientes")
public record MisTerminosResponse(
		@Schema(description = "Version que hay que aceptar, o null si el despliegue no exige terminos",
				example = "2026-11-19", nullable = true)
		String versionVigente,
		@Schema(description = "Donde se publica el texto de los terminos", example = "/terminos", nullable = true)
		String url,
		@Schema(description = "true si la cuenta todavia no acepto la version vigente: hasta aceptarla, "
				+ "la API responde 403 terminos-pendientes")
		boolean pendiente,
		@Schema(description = "Cuando acepto la version vigente, o null si no la acepto", nullable = true)
		Instant aceptadaEn) {

	public static MisTerminosResponse de(TerminosService.Estado estado) {
		return new MisTerminosResponse(estado.versionVigente(), estado.url(), estado.pendiente(),
				estado.aceptadaEn());
	}
}
