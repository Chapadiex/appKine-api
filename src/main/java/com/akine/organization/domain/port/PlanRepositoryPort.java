package com.akine.organization.domain.port;

import com.akine.organization.domain.Plan;

import java.util.List;
import java.util.Optional;

/**
 * Acceso al catalogo de planes.
 *
 * <p>Sin filtro por tenant porque el catalogo es global de plataforma: es una de las
 * excepciones documentadas a la regla de alcance tenant, no un olvido.
 */
public interface PlanRepositoryPort {

	/**
	 * Incluye los planes retirados. Hace falta: una suscripcion vieja los referencia y hay que
	 * poder mostrar con que plan opera.
	 */
	Optional<Plan> findById(Long id);

	/**
	 * Plan CONTRATABLE. Distinto de {@link #findById}: un plan retirado sigue existiendo pero
	 * no se puede contratar de nuevo.
	 */
	Optional<Plan> findByCodeAndActiveTrue(String code);

	/** Oferta vigente, para el listado publico de planes. */
	List<Plan> findAllByActiveTrueOrderByCodeAsc();
}
