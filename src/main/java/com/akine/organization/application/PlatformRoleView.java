package com.akine.organization.application;

import com.akine.organization.domain.PlatformRole;

import java.time.Instant;

/**
 * Proyeccion de lectura de un rol de plataforma.
 *
 * <p>No incluye ningun dato de la cuenta mas alla de su id: resolver el email es de
 * {@code identity} y {@code organization} no puede llegar hasta ahi. La capa {@code api} que
 * quiera mostrar nombres los compone desde {@code identity.spi.AccountDirectory}.
 */
public record PlatformRoleView(
		long id,
		long accountId,
		String roleCode,
		Long grantedByAccountId,
		String reason,
		Instant validFrom,
		Instant validUntil,
		boolean active) {

	public static PlatformRoleView de(PlatformRole rol) {
		return new PlatformRoleView(
				rol.getId(),
				rol.getAccountId(),
				rol.getRoleCode().name(),
				rol.getGrantedByAccountId(),
				rol.getReason(),
				rol.getValidFrom(),
				rol.getValidUntil(),
				rol.isActive());
	}
}
