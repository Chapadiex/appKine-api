package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SesionRepository extends JpaRepository<Sesion, Long>, SesionRepositoryPort {

	@Override
	@Query("""
			SELECT s FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.consultorioId = :consultorioId
			   AND s.id = :sesionId
			""")
	Optional<Sesion> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("sesionId") long sesionId);

	/**
	 * <p>Sin filtro por sede a proposito: un turno pertenece a UNA sede, asi que agregarlo no
	 * acota nada y abriria la puerta a que un llamador pase la sede equivocada y reciba
	 * {@code empty} en vez de la sesion que existe — lo que haria que el segundo inicio creara una
	 * segunda sesion y chocara contra el unique.
	 */
	@Override
	@Query("""
			SELECT s FROM Sesion s
			 WHERE s.organizationId = :organizationId
			   AND s.turnoId = :turnoId
			   AND s.deletedAt IS NULL
			""")
	Optional<Sesion> findVivaPorTurno(
			@Param("organizationId") long organizationId,
			@Param("turnoId") long turnoId);
}
