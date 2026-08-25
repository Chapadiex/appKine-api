package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Rechazo de una invitacion.
 *
 * <p>El motivo es <b>opcional</b>, a diferencia del de la cancelacion: ver
 * {@link CancelInvitacionRequest}.
 *
 * @param token  token del enlace, en claro
 * @param reason motivo, si el invitado quiere darlo. Queda en la invitacion y en la auditoria
 */
@Schema(description = "Rechazo de una invitacion recibida")
public record DeclineInvitacionRequest(

		@Schema(
				description = "Token recibido por correo",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El token de la invitacion es obligatorio")
		@Size(max = 512, message = "El token no puede superar los 512 caracteres")
		String token,

		@Schema(
				description = "Motivo del rechazo. Opcional",
				example = "Ya no estoy disponible")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String reason) {
}
