package com.akine.identity.api.dto;

import com.akine.identity.application.InvitacionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Una invitacion como la ve el administrador que la emitio.
 *
 * <p><b>No lleva el token ni su hash</b>, y no es un olvido: el listado no necesita la credencial
 * del invitado, y exponerla convertiria un permiso de lectura en la capacidad de aceptar
 * invitaciones ajenas. El reenvio tampoco la devuelve — el token nuevo va al correo y a ningun
 * lado mas.
 *
 * @param vencida derivado del reloj del servidor y no de una columna. Una invitacion vencida
 *                sigue figurando PENDIENTE porque expirar no es una decision de nadie, y por eso
 *                el campo existe: sin el, la pantalla tendria que comparar fechas contra el
 *                reloj del navegador, que esta en otra zona horaria
 */
@Schema(description = "Invitacion emitida por la organizacion")
public record InvitacionResponse(

		@Schema(example = "12") long id,
		@Schema(example = "1") long organizationId,
		@Schema(description = "Sede del vinculo propuesto, o null si es de toda la organizacion")
		Long consultorioId,
		@Schema(example = "kine@centro.test") String email,
		@Schema(example = "PROFESIONAL") String roleCode,
		@Schema(description = "PENDIENTE, ACEPTADA, RECHAZADA o CANCELADA", example = "PENDIENTE")
		String estado,
		@Schema(description = "Si el enlace ya vencio. Puede ser true con estado PENDIENTE")
		boolean vencida,
		Instant expiraEn,
		@Schema(description = "Cuando dejo de estar pendiente. Null mientras lo este")
		Instant resueltaEn,
		@Schema(description = "Motivo del rechazo o de la cancelacion")
		String resolucionNota,
		@Schema(description = "Cuenta del administrador que la emitio") long invitadaPorAccountId,
		@Schema(description = "Cuenta que acepto, si alguien acepto") Long aceptadaPorAccountId,
		@Schema(description = "Vinculo creado al aceptar, si se acepto") Long membershipId,
		@Schema(description = "Version para el control de concurrencia optimista") long version,
		Instant createdAt) {

	public static InvitacionResponse de(InvitacionView view) {
		return new InvitacionResponse(
				view.id(),
				view.organizationId(),
				view.consultorioId(),
				view.email(),
				view.roleCode(),
				view.estado().name(),
				view.vencida(),
				view.expiraEn(),
				view.resueltaEn(),
				view.resolucionNota(),
				view.invitadaPorAccountId(),
				view.aceptadaPorAccountId(),
				view.membershipId(),
				view.version(),
				view.createdAt());
	}
}
