package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.PlanTratamientoVersion;
import com.akine.clinical.domain.port.PlanRepositoryPorts.PlanTratamientoVersionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia de las versiones de contenido del plan.
 *
 * <p><b>No expone ningun borrado</b>, ni siquiera el {@code delete} heredado se usa: una version es
 * un hecho pasado y darla de baja seria reescribir historia clinica (ADR-0011). Mismo diseño que
 * {@link EntradaClinicaVersionRepository}.
 *
 * <p>El historico se lee de la mas nueva a la mas vieja —es una bandeja, no una linea de tiempo—,
 * al reves que el historial de estados. Es el mismo criterio que el historico de versiones de una
 * entrada clinica: lo primero que quiere ver quien lo abre es que dice el plan hoy.
 */
public interface PlanTratamientoVersionRepository
		extends JpaRepository<PlanTratamientoVersion, Long>,
		PlanTratamientoVersionRepositoryPort {

	@Override
	@Query("""
			SELECT v FROM PlanTratamientoVersion v
			 WHERE v.organizationId = :organizationId
			   AND v.planTratamientoId = :planTratamientoId
			 ORDER BY v.numeroVersion DESC
			""")
	List<PlanTratamientoVersion> buscarDePlan(
			@Param("organizationId") Long organizationId,
			@Param("planTratamientoId") Long planTratamientoId);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Se ordena por {@code numeroVersion} y no por {@code id}: son el mismo orden hoy, y el
	 * numero es el que el contrato promete. Si algun dia una version se escribiera fuera de orden,
	 * la vigente sigue siendo la de numero mas alto.
	 */
	@Override
	@Query("""
			SELECT v FROM PlanTratamientoVersion v
			 WHERE v.organizationId = :organizationId
			   AND v.planTratamientoId = :planTratamientoId
			 ORDER BY v.numeroVersion DESC
			 LIMIT 1
			""")
	Optional<PlanTratamientoVersion> buscarVigente(
			@Param("organizationId") Long organizationId,
			@Param("planTratamientoId") Long planTratamientoId);

	@Override
	@Query("""
			SELECT v FROM PlanTratamientoVersion v
			 WHERE v.organizationId = :organizationId
			   AND v.planTratamientoId = :planTratamientoId
			   AND v.numeroVersion = :numeroVersion
			""")
	Optional<PlanTratamientoVersion> buscarPorNumero(
			@Param("organizationId") Long organizationId,
			@Param("planTratamientoId") Long planTratamientoId,
			@Param("numeroVersion") int numeroVersion);
}
