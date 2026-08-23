package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Pedido de restablecimiento de contrasena (RF-M02-003).
 *
 * <p>Como en el login, no se valida el formato del email: un 400 por "no parece una direccion"
 * frente a un 202 por "parece una" seria una diferencia observable, y encima gratuita para
 * quien sondea. Todo lo que no venga vacio entra al mismo camino.
 */
@Schema(description = "Direccion para la que se pide restablecer la contrasena")
public record PasswordResetRequest(

		@Schema(
				description = "Direccion de correo de la cuenta",
				example = "ana.perez@ejemplo.test",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El email es obligatorio")
		@Size(max = 254, message = "El email no puede superar los 254 caracteres")
		String email) {
}
