package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Texto vigente del consentimiento de IA")
public record TextoConsentimientoIaResponse(

		@Schema(description = "Version del texto; vacia si el despliegue no exige consentimiento",
				example = "2026-11-26", requiredMode = Schema.RequiredMode.REQUIRED)
		String version,

		@Schema(description = "Texto en parrafos separados por una linea en blanco; las lineas que empiezan "
				+ "con \"- \" son puntos de una lista", requiredMode = Schema.RequiredMode.REQUIRED)
		String texto) {
}
