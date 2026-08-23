package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Confirmacion del restablecimiento: token del correo mas contrasena nueva (RF-M02-003).
 *
 * <p>Confirmar <b>revoca todas las sesiones vivas de la cuenta</b>, en la misma transaccion.
 * Es el punto del sistema donde se asume que la credencial anterior pudo estar comprometida:
 * dejar viva una sesion abierta con la contrasena vieja haria inutil el cambio.
 */
@Schema(description = "Token del enlace de restablecimiento y contrasena nueva")
public record PasswordResetConfirmRequest(

		@Schema(
				description = "Valor del enlace recibido por correo",
				example = "9f8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El token es obligatorio")
		@Size(max = 256, message = "El token no puede superar los 256 caracteres")
		String token,

		@Schema(
				description = "Contrasena nueva. La politica minima la valida el backend",
				example = "otra-contrasena-larga-y-unica",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "La contrasena nueva es obligatoria")
		@Size(max = 256, message = "La contrasena no puede superar los 256 caracteres")
		String password) {
}
