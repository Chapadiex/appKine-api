package com.akine.organization.spi;

import java.util.Optional;

/**
 * Busqueda de una membership por id dentro de un tenant.
 *
 * <p>Filtra SIEMPRE por {@code organizationId}: una membership de otro tenant no resuelve, y
 * quien la pidio recibe 404, nunca 403.
 */
public interface MembershipDirectory {

	Optional<ConsultorioMembershipSnapshot> find(long organizationId, long membershipId);
}
