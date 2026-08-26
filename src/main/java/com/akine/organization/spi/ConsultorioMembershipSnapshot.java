package com.akine.organization.spi;

import java.time.Instant;

/**
 * Vista de una membership para los modulos que necesitan saber DONDE y CUANDO vale.
 *
 * <p>Existe aparte de {@link MembershipSnapshot} y no como una ampliacion suya por un motivo
 * concreto: aquel record ya lo consume {@code identity}, y agregarle componentes rompe todos
 * sus constructores. Un record nuevo no rompe nada.
 *
 * @param consultorioId sede del vinculo, o {@code null} si el alcance es la organizacion
 *                      entera. {@code null} significa <b>alcance</b>, no dato faltante:
 *                      la misma convencion de {@code membership} (V10) y de
 *                      {@code colaborador_invitacion} (V21)
 */
public record ConsultorioMembershipSnapshot(
		long membershipId,
		long accountId,
		long organizationId,
		Long consultorioId,
		String roleCode,
		Instant validFrom,
		Instant validUntil,
		boolean active) {

	/** Vigencia Y baja logica, igual que {@link MembershipSnapshot#validAt(Instant)}. */
	public boolean validAt(Instant momento) {
		if (!active || momento.isBefore(validFrom)) {
			return false;
		}
		return validUntil == null || momento.isBefore(validUntil);
	}

	/** {@code true} si el vinculo habilita en esa sede: la propia, o alcance organizacion. */
	public boolean cubreConsultorio(long consultorioId) {
		return this.consultorioId == null || this.consultorioId == consultorioId;
	}
}
