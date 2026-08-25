package com.akine.identity.application;

import com.akine.identity.domain.ColaboradorInvitacion;
import com.akine.identity.domain.EstadoInvitacion;

import java.time.Instant;

/**
 * Una invitacion como la ve <b>el administrador</b> que la emitio (RF-M05-001).
 *
 * <p><b>No lleva el token ni su hash.</b> El listado del administrador no necesita la credencial
 * del invitado, y exponerla convertiria un permiso de lectura en la capacidad de aceptar
 * invitaciones ajenas. Reenviar tampoco lo devuelve: el token nuevo va al correo y a ningun
 * lado mas.
 *
 * @param vencida derivado del reloj, no de la columna: ver {@code EstadoInvitacion}
 */
public record InvitacionView(
		long id,
		long organizationId,
		Long consultorioId,
		String email,
		String roleCode,
		EstadoInvitacion estado,
		boolean vencida,
		Instant expiraEn,
		Instant resueltaEn,
		String resolucionNota,
		long invitadaPorAccountId,
		Long aceptadaPorAccountId,
		Long membershipId,
		long version,
		Instant createdAt) {

	public static InvitacionView de(ColaboradorInvitacion invitacion, Instant ahora) {
		return new InvitacionView(
				invitacion.getId(),
				invitacion.getOrganizationId(),
				invitacion.getConsultorioId(),
				invitacion.getEmailNormalizado(),
				invitacion.getRoleCode(),
				invitacion.getEstado(),
				invitacion.estaVencida(ahora),
				invitacion.getExpiraEn(),
				invitacion.getResueltaEn(),
				invitacion.getResolucionNota(),
				invitacion.getInvitadaPorAccountId(),
				invitacion.getAceptadaPorAccountId(),
				invitacion.getMembershipId(),
				invitacion.getVersion() == null ? 0L : invitacion.getVersion(),
				invitacion.getCreatedAt());
	}
}
