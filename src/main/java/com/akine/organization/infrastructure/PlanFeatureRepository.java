package com.akine.organization.infrastructure;

import com.akine.organization.domain.PlanFeature;
import com.akine.organization.domain.port.PlanFeatureRepositoryPort;
import com.akine.organization.spi.FeatureCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a las features de un plan.
 *
 * <p>La habilitacion es PRESENCIA de fila activa: por eso la pregunta natural es un
 * {@code exists} y no la lectura de un booleano.
 */
public interface PlanFeatureRepository extends JpaRepository<PlanFeature, Long>, PlanFeatureRepositoryPort {

	/** Features vigentes del plan, para exponer que incluye la suscripcion. */
	List<PlanFeature> findAllByPlanIdAndActiveTrue(Long planId);

	/** La pregunta del feature gate: esta habilitada esta funcionalidad. */
	boolean existsByPlanIdAndFeatureCodeAndActiveTrue(Long planId, FeatureCode featureCode);

	/**
	 * Incluye las filas dadas de baja, para reactivar en lugar de insertar y chocar contra
	 * {@code uk_plan_feature}.
	 */
	Optional<PlanFeature> findByPlanIdAndFeatureCode(Long planId, FeatureCode featureCode);
}
