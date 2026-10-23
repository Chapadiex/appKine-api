package com.akine.organization.infrastructure;

import com.akine.organization.domain.Organization;
import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.domain.OrganizationListing;
import com.akine.organization.domain.SubscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

	/**
	 * Una sola consulta con dos joins de entidad, porque {@code Subscription} y {@code Plan} se
	 * referencian por id y no por asociacion: sin el join explicito, armar cada fila costaria
	 * dos lecturas mas (N+1 sobre todos los tenants).
	 *
	 * <p>El {@code LIKE} no lleva clausula {@code ESCAPE}: el patron llega escapado con barra
	 * invertida, que es el escape por defecto de MySQL, igual que en
	 * {@code FinanciadorRepository.buscar}. La comparacion no distingue mayusculas porque la
	 * collation de las columnas tampoco.
	 *
	 * <p>DP-45: el orden lo trae el {@code Pageable} —antes era {@code order by o.name, o.id} fijo, y
	 * un orden pedido se agregaba detras—. El controller pide ese mismo orden cuando no hay sort.
	 */
	@Override
	@Query(value = """
			select new com.akine.organization.domain.OrganizationListing(
			         o.id, o.name, o.slug, s.status, p.code, p.name, o.createdAt)
			  from Organization o
			  join Subscription s on s.organizationId = o.id
			  join Plan p on p.id = s.planId
			 where o.active = true
			   and (:status is null or s.status = :status)
			   and (o.name like :patron or o.slug like :patron)
			""",
			countQuery = """
			select count(o)
			  from Organization o
			  join Subscription s on s.organizationId = o.id
			 where o.active = true
			   and (:status is null or s.status = :status)
			   and (o.name like :patron or o.slug like :patron)
			""")
	Page<OrganizationListing> listForPlatform(
			@Param("patron") String patron,
			@Param("status") SubscriptionStatus status,
			Pageable pageable);
}
