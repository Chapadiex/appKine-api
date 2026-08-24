package com.akine.organization.spi;

import java.time.Instant;

/**
 * Lo que hay que saber para decidir un permiso: quien, que, sobre que, y cuando.
 *
 * <p><b>Sobre el nombre.</b> El diseño de AKINE-01.03 lo llamaba {@code AuthorizationRequest};
 * no puede llamarse asi. La convencion {@code dtos_agrupados}, verificada por
 * {@code CodingConventionsTest}, obliga a que todo lo que TERMINA en {@code Request} viva en
 * {@code ..api.dto..}, y esto es un contrato del {@code spi}, no un DTO de transporte. Es el
 * mismo motivo por el que {@code platform.spi.tenant.RequestTenantContext} se llama asi y no
 * {@code TenantContextRequest}: el sufijo es el que manda.
 *
 * <p>{@code permissionCode} viaja como {@code String} y no como el enum de {@code domain}: un
 * consumidor que usara el enum estaria importando {@code organization.domain}, que ArchUnit
 * rechaza. Es la misma decision, y por el mismo motivo, que {@code MembershipSnapshot}.
 *
 * @param accountId        cuenta que opera
 * @param permissionCode   codigo del catalogo de la matriz seccion 5, p.ej. {@code colaborador:manage}
 * @param organizationId   tenant sobre el que se decide, o {@code null} si el request no trae
 *                         contexto. {@code null} no es "cualquiera": produce
 *                         {@link DenialKind#NO_CONTEXT}
 * @param consultorioId    sede sobre la que se decide, o {@code null} para una decision de
 *                         alcance organizacion
 * @param targetAccountId  cuenta sobre la que se opera, cuando la operacion apunta a una
 *                         persona. La matriz decide QUE puede hacer el actor; la membership del
 *                         objetivo sigue decidiendo SOBRE QUIEN (ADR-0019)
 * @param at               instante contra el que se evalua toda vigencia (UTC)
 */
public record PermissionQuery(
		long accountId,
		String permissionCode,
		Long organizationId,
		Long consultorioId,
		Long targetAccountId,
		Instant at) {

	public PermissionQuery {
		if (permissionCode == null || permissionCode.isBlank()) {
			throw new IllegalArgumentException("permissionCode es obligatorio para decidir un permiso");
		}
		if (at == null) {
			throw new IllegalArgumentException(
					"at es obligatorio: toda vigencia se evalua contra un instante explicito, "
							+ "nunca contra un reloj implicito");
		}
	}

	/** Consulta sin objetivo ni sede: la forma mas comun, sobre la organizacion del contexto. */
	public static PermissionQuery of(
			long accountId, String permissionCode, Long organizationId, Instant at) {
		return new PermissionQuery(accountId, permissionCode, organizationId, null, null, at);
	}
}
