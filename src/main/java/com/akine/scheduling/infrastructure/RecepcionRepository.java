package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.Recepcion;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.RecepcionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Persistencia de la recepcion (M13, AKINE E-4). */
public interface RecepcionRepository extends JpaRepository<Recepcion, Long>, RecepcionRepositoryPort {

	/**
	 * <p>"Vigente" es todo lo que no es ANULADA, incluida la CERRADA: es exactamente lo que el
	 * unique {@code uk_recepcion_turno_vigente} de V78 deja de a uno por turno.
	 */
	@Override
	@Query("""
			SELECT r FROM Recepcion r
			 WHERE r.organizationId = :organizationId
			   AND r.turnoId = :turnoId
			   AND r.estado <> com.akine.scheduling.domain.EstadoRecepcion.ANULADA
			""")
	Optional<Recepcion> findVigente(
			@Param("organizationId") long organizationId,
			@Param("turnoId") long turnoId);

	@Override
	@Query("""
			SELECT r FROM Recepcion r
			 WHERE r.organizationId = :organizationId
			   AND r.turnoId IN :turnoIds
			   AND r.estado <> com.akine.scheduling.domain.EstadoRecepcion.ANULADA
			""")
	List<Recepcion> findVigentesDeTurnos(
			@Param("organizationId") long organizationId,
			@Param("turnoIds") Collection<Long> turnoIds);
}
