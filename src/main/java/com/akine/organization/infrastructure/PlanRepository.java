package com.akine.organization.infrastructure;

import com.akine.organization.domain.Plan;
import com.akine.organization.domain.port.PlanRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Acceso al catalogo de planes.
 *
 * <p>Sin filtro por {@code organizationId} porque el catalogo es global de plataforma: es una
 * de las cuatro excepciones documentadas a la regla de alcance tenant, no un olvido.
 */
public interface PlanRepository extends JpaRepository<Plan, Long>, PlanRepositoryPort {

	/** Busca por la clave estable que usan el seed y el spi de onboarding. */
	Optional<Plan> findByCode(String code);

	/**
	 * Busca un plan contratable.
	 *
	 * <p>Distinto de {@link #findByCode}: un plan retirado sigue existiendo —hay
	 * suscripciones que lo referencian— pero no se puede contratar de nuevo.
	 */
	Optional<Plan> findByCodeAndActiveTrue(String code);

	/** Oferta vigente, para el listado publico de planes. */
	List<Plan> findAllByActiveTrueOrderByCodeAsc();
}
