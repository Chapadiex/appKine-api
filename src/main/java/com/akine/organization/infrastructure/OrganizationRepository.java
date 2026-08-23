package com.akine.organization.infrastructure;

import com.akine.organization.domain.Organization;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Acceso a la tabla {@code organization}, propiedad del modulo {@code organization}.
 *
 * <p>Es la unica entidad sin filtro por {@code organizationId}: aca el id ES el tenant. Todo
 * el resto de los repositorios del modulo filtra por organizacion en el {@code WHERE}, sin
 * excepcion.
 */
public interface OrganizationRepository extends JpaRepository<Organization, Long>, OrganizationRepositoryPort {

	/**
	 * Busca una organizacion vigente.
	 *
	 * <p>Filtra por {@code active} a proposito: una organizacion dada de baja no debe
	 * resolverse como contexto de trabajo. Para la consulta administrativa de plataforma
	 * —que si tiene que ver las dadas de baja— se usa {@code findById}.
	 */
	Optional<Organization> findByIdAndActiveTrue(Long id);

	/** El slug es unico global: es lo que distingue un tenant de otro. */
	Optional<Organization> findBySlug(String slug);

	/**
	 * Chequeo previo de disponibilidad del slug, para responder un 409 con mensaje util.
	 *
	 * <p>No reemplaza a {@code uk_organization_slug}: entre este SELECT y el INSERT hay una
	 * ventana, y quien cierra la carrera es la restriccion.
	 */
	boolean existsBySlug(String slug);
}
