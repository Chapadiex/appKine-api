package com.akine.organization.domain.port;

import com.akine.organization.domain.Organization;

import java.util.Optional;

/**
 * Acceso a la tabla {@code organization}, propiedad del modulo {@code organization}.
 *
 * <p>Es la unica entidad sin filtro por tenant: aca el id ES el tenant.
 */
public interface OrganizationRepositoryPort {

	Organization save(Organization organization);

	/**
	 * Incluye las dadas de baja. La necesita el calculo del estado operativo, que tiene que
	 * distinguir BAJA de CANCELADA, y la administracion de plataforma.
	 */
	Optional<Organization> findById(Long id);

	/**
	 * Organizacion vigente. Una dada de baja no se resuelve como contexto de trabajo, y para
	 * quien pregunta es indistinguible de una inexistente.
	 */
	Optional<Organization> findByIdAndActiveTrue(Long id);

	/**
	 * Chequeo previo de disponibilidad del slug.
	 *
	 * <p>No reemplaza a {@code uk_organization_slug}: entre este SELECT y el INSERT hay una
	 * ventana, y quien cierra la carrera es la restriccion. Sirve para dar un mensaje util, no
	 * para garantizar unicidad.
	 */
	boolean existsBySlug(String slug);
}
