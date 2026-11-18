package com.akine.organization.domain.port;

import com.akine.organization.domain.OrganizationIa;

import java.util.Optional;

/** Acceso a {@code organization_ia}: a lo sumo una fila por tenant. */
public interface OrganizationIaRepositoryPort {

	Optional<OrganizationIa> findByOrganizationId(Long organizationId);

	/** Crea la fila con todo prendido si no existe ({@code ON CONFLICT DO NOTHING}). */
	void asegurarFila(Long organizationId);

	/** Con flush: la respuesta tiene que llevar la version ya incrementada. */
	OrganizationIa saveAndFlush(OrganizationIa ia);
}
