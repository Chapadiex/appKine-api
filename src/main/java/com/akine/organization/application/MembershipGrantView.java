package com.akine.organization.application;

import com.akine.organization.domain.MembershipGrant;
import com.akine.organization.domain.RoleCode;
import com.akine.organization.domain.RolePermissions;

import java.time.Instant;

/**
 * Proyeccion de lectura de un permiso adicional.
 *
 * <p>{@code reason} sale en la vista a proposito: un permiso adicional que no se puede explicar
 * es un permiso que nadie va a atreverse a revocar. La pantalla de colaboradores lo muestra
 * junto al permiso, no escondido en la auditoria.
 *
 * <p>{@code vigenteConElRolActual} es la misma pregunta que se hace el evaluador al leer
 * ({@code RolePermissions.otorgableComoGrant} contra el rol ACTUAL de la membership): un grant
 * otorgado antes de G-5, o antes de un cambio de rol, sigue activo en la fila y no concede nada.
 * Sin este dato la pantalla lo mostraria como si valiera.
 */
public record MembershipGrantView(
		long id,
		long membershipId,
		String permissionCode,
		long grantedByAccountId,
		String reason,
		Instant validFrom,
		Instant validUntil,
		boolean active,
		boolean vigenteConElRolActual) {

	public static MembershipGrantView de(MembershipGrant grant, RoleCode rolActual) {
		return new MembershipGrantView(
				grant.getId(),
				grant.getMembershipId(),
				grant.getPermissionCode().code(),
				grant.getGrantedByAccountId(),
				grant.getReason(),
				grant.getValidFrom(),
				grant.getValidUntil(),
				grant.isActive(),
				RolePermissions.otorgableComoGrant(rolActual, grant.getPermissionCode()));
	}
}
