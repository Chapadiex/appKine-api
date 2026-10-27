package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Perfil de la cuenta autenticada (DP-45 punto 3): lo que la barra lateral muestra de quien
 * ingreso. Solo datos que la persona ya conoce de si misma; ni hash, ni tokens, ni estado
 * interno de la cuenta.
 */
@Schema(description = "Perfil propio: cuenta y, si hay contexto de trabajo activo, organizacion, "
		+ "sede y rol")
public record MyProfileResponse(

		@Schema(description = "Identificador de la cuenta", example = "42")
		long accountId,

		@Schema(description = "Correo con el que ingreso", example = "ana@centro.test")
		String email,

		@Schema(description = "Nombre", example = "Ana")
		String firstName,

		@Schema(description = "Apellido", example = "Perez")
		String lastName,

		@Schema(description = "Contexto de trabajo activo del request. Null si el token todavia no "
				+ "esta acotado a un contexto (pre_context) o si es un administrador de plataforma "
				+ "sin contexto", nullable = true)
		ActiveWorkContext context) {

	@Schema(description = "Organizacion, sede y rol de la cuenta en el contexto activo")
	public record ActiveWorkContext(

			@Schema(description = "Identificador de la organizacion", example = "7")
			long organizationId,

			@Schema(description = "Nombre de la organizacion", example = "Centro Kine")
			String organizationName,

			@Schema(description = "Identificador de la sede", example = "11")
			long consultorioId,

			@Schema(description = "Nombre de la sede", example = "Sede Centro")
			String consultorioName,

			@Schema(description = "Codigo del rol de la membership en este contexto",
					example = "ORG_ADMIN")
			String roleCode,

			@Schema(description = "Nombre del rol para mostrar",
					example = "Administrador de la organizacion")
			String roleLabel) {
	}
}
