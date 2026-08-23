package com.akine.organization.domain.port;

import com.akine.organization.domain.PlanLimit;
import com.akine.organization.spi.LimitCode;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a los limites de un plan. Las consultas filtran por {@code active}: un limite dado de
 * baja sigue en la tabla para reconstruir que incluia el plan en su momento, pero no participa
 * de la evaluacion de hoy.
 */
public interface PlanLimitRepositoryPort {

	/** Limites vigentes del plan, para mostrar la suscripcion con su uso. */
	List<PlanLimit> findAllByPlanIdAndActiveTrue(Long planId);

	/**
	 * Limite concreto a evaluar en un alta.
	 *
	 * <p>Ausencia de fila y {@code limit_value = NULL} NO son lo mismo: la ausencia significa
	 * que el limite no aplica a ese plan, el NULL significa ilimitado explicito. Los dos
	 * permiten el alta, pero por motivos distintos y con evoluciones distintas.
	 */
	Optional<PlanLimit> findByPlanIdAndLimitCodeAndActiveTrue(Long planId, LimitCode limitCode);
}
