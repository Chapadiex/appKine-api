package com.akine.platform.api.dto;

import com.akine.platform.domain.BuildVersion;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Respuesta del contrato tecnico de versionado.
 *
 * <p>Los DTO son lo unico que cruza el borde del backend. Ninguna entity ni objeto de
 * dominio se serializa directamente hacia el cliente (AGENT.md seccion 4).
 */
@Schema(description = "Identidad tecnica del backend y version del contrato OpenAPI publicado")
public record VersionResponse(

		@Schema(description = "Nombre de la aplicacion", example = "akine-api")
		String application,

		@Schema(description = "Version de la aplicacion", example = "0.0.1-SNAPSHOT")
		String version,

		@Schema(description = "Version SemVer del contrato OpenAPI publicado", example = "0.1.0")
		String contract) {

	public static VersionResponse from(BuildVersion buildVersion) {
		return new VersionResponse(
				buildVersion.applicationName(),
				buildVersion.applicationVersion(),
				buildVersion.contractVersion());
	}
}
