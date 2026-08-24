package com.akine.organization.application;

import com.akine.organization.domain.Membership;
import com.akine.platform.spi.identity.AccountIdentity;

import java.time.Instant;

/**
 * Proyeccion de lectura de una membership.
 *
 * <p>Las entities nunca cruzan el borde del service (AGENT.md §4). Este record es lo que la capa
 * {@code api} convierte en DTO y lo unico que sale de {@code application}.
 *
 * <p>Se expone la vigencia cruda ademas del estado porque el listado de colaboradores tiene que
 * poder explicar <b>por que</b> alguien no tiene acceso: "revocada el 3 de marzo por X" y
 * "vencida el 1 de enero" son dos respuestas distintas y la pantalla las da distintas.
 *
 * <p><b>El nombre y el email de la cuenta viajan aca y no en una segunda llamada.</b> Sin ellos
 * la pantalla de colaboradores es una lista de numeros: cada fila referencia a una persona y no
 * hay forma de decir cual. Se resuelven en {@link MembershipService} contra
 * {@link AccountIdentity}, en <b>una sola</b> consulta por pagina.
 *
 * <p>Los dos campos son {@code null} cuando la cuenta no se pudo resolver. No deberia pasar
 * —la fila de {@code cuenta} nunca se borra— pero la referencia es logica, sin clave foranea
 * fisica (ADR-0001), asi que el caso se representa en vez de reventar un listado entero por una
 * fila huerfana.
 *
 * @param roleCode     rol como texto: el enum es privado del modulo
 * @param estado       estado del vinculo como texto, por el mismo motivo
 * @param accountName  nombre completo de la cuenta vinculada, o {@code null} si no se resolvio
 * @param accountEmail email de la cuenta vinculada, o {@code null} si no se resolvio
 */
public record MembershipView(
		long id,
		long organizationId,
		Long consultorioId,
		long accountId,
		String roleCode,
		String estado,
		boolean founder,
		Instant validFrom,
		Instant validUntil,
		boolean active,
		Long revokedByAccountId,
		String revokedReason,
		String accountName,
		String accountEmail) {

	/** Vista sin la identidad resuelta: la usan los caminos que no la necesitan. */
	public static MembershipView de(Membership membership) {
		return de(membership, null);
	}

	/** Vista con la identidad de la cuenta ya resuelta por el llamador. */
	public static MembershipView de(Membership membership, AccountIdentity cuenta) {
		return new MembershipView(
				membership.getId(),
				membership.getOrganizationId(),
				membership.getConsultorioId(),
				membership.getAccountId(),
				membership.getRoleCode().name(),
				membership.getEstado().name(),
				membership.isFounder(),
				membership.getValidFrom(),
				membership.getValidUntil(),
				membership.isActive(),
				membership.getRevokedByAccountId(),
				membership.getRevokedReason(),
				cuenta == null ? null : cuenta.nombreCompleto(),
				cuenta == null ? null : cuenta.email());
	}
}
