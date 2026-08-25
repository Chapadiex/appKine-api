package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Retiro de una invitacion pendiente.
 *
 * <p>El motivo es obligatorio y el rechazo del invitado no lo exige. No es una asimetria
 * caprichosa: cancelar es una decision del administrador sobre una persona a la que ya le
 * escribio, y tiene que responder por que seis meses despues. Al invitado no se le pide que
 * explique por que no quiere entrar a trabajar a un lado.
 *
 * @param reason motivo declarado. Queda en la invitacion y en la auditoria
 */
@Schema(description = "Motivo con el que se retira una invitacion pendiente")
public record CancelInvitacionRequest(

		@Schema(
				description = "Motivo declarado de la cancelacion",
				example = "Se equivoco de direccion de correo",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la cancelacion es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String reason) {
}
