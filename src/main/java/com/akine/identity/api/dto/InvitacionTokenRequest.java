package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Token de una invitacion, para consultarla antes de decidir.
 *
 * <h2>Por que es un POST con el token en el cuerpo y no un GET con el token en la URL</h2>
 *
 * <p>Un token en la query string queda en los logs de acceso del servidor, en el historial del
 * navegador, en el {@code Referer} de cualquier recurso externo que la pantalla cargue y en el
 * historial de proxies intermedios. Es la misma decision que ya tomaron
 * {@code POST /auth/activate} y {@code POST /auth/password-reset/confirm}, y por los mismos
 * motivos.
 *
 * <p><b>Consultar no consume la invitacion.</b> Un enlace que se gasta al mirarlo deja al
 * invitado sin poder aceptar en cuanto recargue la pantalla, o en cuanto su cliente de correo
 * lo pre-visite para generar una vista previa.
 *
 * @param token token del enlace, en claro. No se persiste ni se loguea en ningun nivel
 */
@Schema(description = "Token del enlace de invitacion")
public record InvitacionTokenRequest(

		@Schema(
				description = "Token recibido por correo",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El token de la invitacion es obligatorio")
		@Size(max = 512, message = "El token no puede superar los 512 caracteres")
		String token) {
}
