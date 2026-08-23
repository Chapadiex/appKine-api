package com.akine.organization.infrastructure;

import com.akine.organization.domain.PlanLimit;
import com.akine.organization.domain.port.PlanLimitRepositoryPort;
import com.akine.organization.spi.LimitCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a los limites de un plan.
 *
 * <p>Las consultas filtran por {@code active}: un limite dado de baja logica sigue en la tabla
 * para poder reconstruir que incluia el plan en su momento, pero no participa de la
 * evaluacion de hoy.
 */
public interface PlanLimitRepository extends JpaRepository<PlanLimit, Long>, PlanLimitRepositoryPort {

	/** Limites vigentes del plan, para mostrar la suscripcion con su uso. */
	List<PlanLimit> findAllByPlanIdAndActiveTrue(Long planId);

	/**
	 * Limite concreto a evaluar en un alta.
	 *
	 * <p>Ausencia de fila y {@code limit_value = NULL} NO son lo mismo: la ausencia significa
	 * que el limite no aplica a ese plan, el NULL significa ilimitado explicito.
	 */
	Optional<PlanLimit> findByPlanIdAndLimitCodeAndActiveTrue(Long planId, LimitCode limitCode);

	/**
	 * Incluye las filas dadas de baja. La usa la administracion del catalogo para reactivar
	 * un limite en vez de insertar uno nuevo, que chocaria contra {@code uk_plan_limit}.
	 */
	Optional<PlanLimit> findByPlanIdAndLimitCode(Long planId, LimitCode limitCode);
}
