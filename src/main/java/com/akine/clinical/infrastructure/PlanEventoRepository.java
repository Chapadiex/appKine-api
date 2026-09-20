package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.PlanEvento;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanEventoRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Persistencia del historial de estados del Plan.
 *
 * <p><b>Append-only.</b> El puerto que implementa no declara {@code update} ni {@code delete}, y
 * esta interfaz no agrega ninguno: un historial que se puede editar no es un historial. Mismo
 * diseño que {@link CasoEventoRepository} y que el historial de turnos de 05.03.
 *
 * <p>Se lee del evento mas viejo al mas nuevo: esto no es una bandeja sino una linea de tiempo, y
 * una linea de tiempo se lee en el orden en que ocurrio.
 */
public interface PlanEventoRepository
		extends JpaRepository<PlanEvento, Long>, PlanEventoRepositoryPort {

	@Override
	@Query("""
			SELECT e FROM PlanEvento e
			 WHERE e.organizationId = :organizationId
			   AND e.planTratamientoId = :planTratamientoId
			 ORDER BY e.ocurrioEn ASC, e.id ASC
			""")
	List<PlanEvento> buscarDePlan(
			@Param("organizationId") Long organizationId,
			@Param("planTratamientoId") Long planTratamientoId);
}
