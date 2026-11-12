package com.akine.organization.domain.port;

import com.akine.organization.domain.OrganizationMarca;

import java.util.Optional;

/** Acceso a {@code organization_marca}: a lo sumo una fila por tenant. */
public interface OrganizationMarcaRepositoryPort {

	Optional<OrganizationMarca> findByOrganizationId(Long organizationId);

	/**
	 * Crea la fila vacia si no existe. {@code ON CONFLICT DO NOTHING}: dos primeras ediciones
	 * simultaneas no chocan contra el unique; la segunda espera y despues compite por version.
	 */
	void asegurarFila(Long organizationId);

	/** Con flush: la respuesta tiene que llevar la version ya incrementada. */
	OrganizationMarca saveAndFlush(OrganizationMarca marca);
}
