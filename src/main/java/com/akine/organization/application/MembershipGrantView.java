package com.akine.organization.application;

import com.akine.organization.domain.MembershipGrant;

import java.time.Instant;

/**
 * Proyeccion de lectura de un permiso adicional.
 *
 * <p>{@code reason} sale en la vista a proposito: un permiso adicional que no se puede explicar
 * es un permiso que nadie va a atreverse a revocar. La pantalla de colaboradores lo muestra
 * junto al permiso, no escondido en la auditoria.
 */
public record MembershipGrantView(
		long id,
		long membershipId,
		String permissionCode,
		long grantedByAccountId,
		String reason,
		Instant validFrom,
		Instant validUntil,
		boolean active) {

	public static MembershipGrantView de(MembershipGrant grant) {
		return new MembershipGrantView(
				grant.getId(),
				grant.getMembershipId(),
				grant.getPermissionCode().code(),
				grant.getGrantedByAccountId(),
				grant.getReason(),
				grant.getValidFrom(),
				grant.getValidUntil(),
				grant.isActive());
	}
}
