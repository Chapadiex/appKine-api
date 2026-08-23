package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Pedido de reenvio del enlace de activacion.
 *
 * <p>Solo la direccion: pedir cualquier otro dato daria una segunda dimension por la cual
 * distinguir respuestas. El endpoint contesta {@code 202} identico exista la cuenta, este ya
 * activa o no exista (ADR-0018).
 */
@Schema(description = "Direccion a la que reenviar el enlace de activacion")
public record ResendActivationRequest(

		@Schema(
				description = "Direccion de correo de la cuenta a activar",
				example = "ana.perez@ejemplo.test",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El email es obligatorio")
		@Size(max = 254, message = "El email no puede superar los 254 caracteres")
		String email) {
}
