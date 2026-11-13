package com.akine.organization.api.dto;

import com.akine.organization.application.MarcaView;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Un logo cargado, sin su contenido. El contenido se pide aparte")
public record LogoDeMarcaResponse(

		@Schema(description = "Tipo de la imagen, decidido por sus bytes", example = "image/png",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String contentType,

		@Schema(description = "Huella SHA-256 del contenido. Cambia cuando cambia el logo: sirve "
				+ "para saber si hay que volver a pedirlo",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String sha256) {

	static LogoDeMarcaResponse from(MarcaView.Logo logo) {
		return logo == null ? null : new LogoDeMarcaResponse(logo.contentType(), logo.sha256());
	}
}
