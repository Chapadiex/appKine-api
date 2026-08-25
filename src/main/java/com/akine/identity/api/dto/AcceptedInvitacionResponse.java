package com.akine.identity.api.dto;

import com.akine.identity.application.ResultadoAceptacion;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Resultado de aceptar una invitacion.
 *
 * <h2>Por que NO devuelve una sesion</h2>
 *
 * <p>Seria comodo: la persona acaba de elegir su contrasena y mandarla al login la obliga a
 * tipearla de nuevo. Se descarta igual, por dos motivos.
 *
 * <p><b>Uno.</b> Aceptar es un endpoint publico, sin sesion previa. Emitir el par de tokens acá
 * duplicaria en un segundo lugar toda la maquinaria de sesion —rotacion estricta del refresh,
 * cookie {@code httpOnly}, validacion de {@code Origin} contra CSRF, registro del dispositivo—
 * que hoy vive concentrada en un solo camino auditado (ADR-0017). Dos implementaciones de lo
 * mismo divergen el dia que una se corrige.
 *
 * <p><b>Dos.</b> Quien acepta con una cuenta que ya tenia <b>no probo que sea suya</b>: probo
 * que llega al buzon. Son casi lo mismo y no son lo mismo — una casilla abierta en una maquina
 * compartida alcanza para lo primero y no para lo segundo. Devolver sesion ahi seria un login
 * sin contrasena.
 *
 * <p>Lo que si se hace es que la pantalla siguiente sepa que decir: {@link #cuentaCreada}
 * distingue "entra con la contrasena que acabas de elegir" de "entra con tu cuenta de siempre".
 */
@Schema(description = "Vinculo creado al aceptar la invitacion")
public record AcceptedInvitacionResponse(

		@Schema(description = "Cuenta que quedo vinculada", example = "42") long cuentaId,
		@Schema(description = "Vinculo creado", example = "17") long membershipId,
		@Schema(example = "1") long organizationId,
		@Schema(description = "Sede del vinculo, o null si es de toda la organizacion")
		Long consultorioId,
		@Schema(description = "Si la cuenta se creo en este mismo acto") boolean cuentaCreada) {

	public static AcceptedInvitacionResponse de(ResultadoAceptacion resultado) {
		return new AcceptedInvitacionResponse(
				resultado.cuentaId(),
				resultado.membershipId(),
				resultado.organizationId(),
				resultado.consultorioId(),
				resultado.cuentaCreada());
	}
}
