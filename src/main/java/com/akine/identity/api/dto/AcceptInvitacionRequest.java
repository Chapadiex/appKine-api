package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Aceptacion de una invitacion (RF-M05-002).
 *
 * <h2>Los tres campos de alta son condicionales, y no hay forma de expresarlo en el schema</h2>
 *
 * <p>{@code nombre} y {@code password} hacen falta <b>solo si el invitado todavia no tiene
 * cuenta</b>. Si ya la tiene, se ignoran: no se le reescriben el nombre ni la contrasena a nadie
 * por aceptar una invitacion — eso seria una via para tomarle la cuenta a otro con solo
 * invitarlo a un centro propio.
 *
 * <p>La pantalla sabe cual de los dos casos es <b>antes</b> de mostrar el formulario, porque
 * {@code POST /auth/invitations/preview} devuelve {@code requiereRegistro}. Por eso los campos
 * no llevan {@code @NotBlank}: exigirlos siempre obligaria a quien ya tiene cuenta a tipear una
 * contrasena nueva para aceptar, y validarlos condicionalmente en Bean Validation daria un
 * mensaje de error que no explica nada. La validacion real vive en el servicio, que es quien
 * sabe si la cuenta existe.
 *
 * @param token    token del enlace, en claro
 * @param nombre   nombre del invitado. Obligatorio solo si hay que crear la cuenta
 * @param apellido apellido del invitado. Opcional siempre
 * @param password contrasena elegida. Obligatoria solo si hay que crear la cuenta, y pasa por
 *                 la misma politica que el registro self-service
 */
@Schema(description = "Aceptacion de una invitacion, con los datos de alta si hacen falta")
public record AcceptInvitacionRequest(

		@Schema(
				description = "Token recibido por correo",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El token de la invitacion es obligatorio")
		@Size(max = 512, message = "El token no puede superar los 512 caracteres")
		String token,

		@Schema(
				description = "Nombre. Obligatorio solo si el invitado no tiene cuenta todavia",
				example = "Ana")
		@Size(max = 120, message = "El nombre no puede superar los 120 caracteres")
		String nombre,

		@Schema(description = "Apellido. Opcional", example = "Gomez")
		@Size(max = 120, message = "El apellido no puede superar los 120 caracteres")
		String apellido,

		@Schema(
				description = "Contrasena elegida. Obligatoria solo si el invitado no tiene "
						+ "cuenta todavia. Misma politica que el registro",
				format = "password")
		@Size(max = 128, message = "La contrasena no puede superar los 128 caracteres")
		String password) {
}
