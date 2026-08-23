package com.akine.organization.domain.port;

import com.akine.organization.domain.PlanFeature;
import com.akine.organization.spi.FeatureCode;

import java.util.List;

/**
 * Acceso a las features de un plan.
 *
 * <p>La habilitacion es PRESENCIA de fila activa, no un booleano: por eso la pregunta natural
 * es un {@code exists}. Agregar una feature nueva no cambia el esquema y ningun plan viejo la
 * hereda por omision.
 */
public interface PlanFeatureRepositoryPort {

	/** Features vigentes del plan, para exponer que incluye la suscripcion. */
	List<PlanFeature> findAllByPlanIdAndActiveTrue(Long planId);

	/** La pregunta del feature gate. */
	boolean existsByPlanIdAndFeatureCodeAndActiveTrue(Long planId, FeatureCode featureCode);
}
