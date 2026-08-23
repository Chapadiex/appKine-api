package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Confirmacion del enlace de activacion (RF-M02-001).
 *
 * <p>El token viaja en el cuerpo y no en la query. Es deliberado: las URLs quedan en el
 * historial del navegador, en el {@code Referer} hacia terceros y en el log de acceso de
 * cualquier proxy intermedio, y este token es una credencial que toma una cuenta. El enlace
 * del correo apunta a una pantalla del frontend que lee el token de la query y lo reenvia por
 * POST; ahi el salto es del cliente, no de la API.
 *
 * <p>La contrasena es opcional porque hay dos origenes: en el alta self-service la cuenta ya
 * tiene credencial y este paso solo confirma la direccion; en una invitacion (01.03) la cuenta
 * nace sin credencial y se fija aca. Mandarla sobre una cuenta que ya la tiene <b>no la
 * cambia</b>: cambiarla por este camino seria una toma de cuenta que esquiva el reset, que si
 * revoca todas las sesiones.
 */
@Schema(description = "Token del enlace de activacion, con la contrasena si hace falta fijarla")
public record ActivateAccountRequest(

		@Schema(
				description = "Valor del enlace recibido por correo",
				example = "9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El token es obligatorio")
		@Size(max = 256, message = "El token no puede superar los 256 caracteres")
		String token,

		@Schema(
				description = "Contrasena a fijar. Obligatoria solo si la cuenta todavia no "
						+ "tiene credencial (invitacion); se ignora si ya la tiene",
				example = "una-contrasena-larga-y-unica",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 256, message = "La contrasena no puede superar los 256 caracteres")
		String password) {
}
