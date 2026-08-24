package com.akine.organization.application;

import com.akine.organization.domain.SupportAccess;

import java.time.Instant;

/**
 * Proyeccion de lectura de un acceso de soporte.
 *
 * <p>{@code reason} y {@code validUntil} salen siempre: son los dos datos que hacen que el
 * acceso sea revisable. Un listado que muestre quien entro y no por que ni hasta cuando no
 * sirve para lo unico para lo que se consulta.
 */
public record SupportAccessView(
		long id,
		long organizationId,
		long accountId,
		String reason,
		long grantedByAccountId,
		Instant validFrom,
		Instant validUntil,
		Instant revokedAt,
		boolean active) {

	public static SupportAccessView de(SupportAccess acceso) {
		return new SupportAccessView(
				acceso.getId(),
				acceso.getOrganizationId(),
				acceso.getAccountId(),
				acceso.getReason(),
				acceso.getGrantedByAccountId(),
				acceso.getValidFrom(),
				acceso.getValidUntil(),
				acceso.getRevokedAt(),
				acceso.isActive());
	}
}
