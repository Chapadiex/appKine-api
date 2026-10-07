package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.TurnoSerie;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoSerieRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TurnoSerieRepository
		extends JpaRepository<TurnoSerie, Long>, TurnoSerieRepositoryPort {

	@Override
	@Query("""
			SELECT s FROM TurnoSerie s
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND s.id = :serieId
			""")
	Optional<TurnoSerie> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("serieId") long serieId);

	@Override
	@Query("""
			SELECT s FROM TurnoSerie s
			 WHERE s.organizationId = :organizationId
			   AND s.idempotencyKey = :idempotencyKey
			""")
	Optional<TurnoSerie> findByIdempotencyKey(
			@Param("organizationId") long organizationId,
			@Param("idempotencyKey") String idempotencyKey);
}
