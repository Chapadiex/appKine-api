package com.akine.billing.infrastructure;

import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ObligacionRepository
		extends JpaRepository<Obligacion, Long>, ObligacionRepositoryPort {

	@Override
	@Query("""
			SELECT o FROM Obligacion o
			 WHERE o.organizationId = :organizationId
			   AND o.consultorioId = :consultorioId
			   AND o.id = :obligacionId
			""")
	Optional<Obligacion> findByIdInScope(
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId,
			@Param("obligacionId") long obligacionId);

	/**
	 * <p>Sin filtro por sede ni por organizacion a proposito: una sesion pertenece a UNA sede, asi
	 * que agregarlos no acota nada y abriria la puerta a que un llamador pase el ambito equivocado y
	 * reciba {@code empty} en vez de la obligacion que existe — con lo cual devengaria una segunda
	 * y chocaria contra el unique.
	 */
	@Override
	@Query("""
			SELECT o FROM Obligacion o
			 WHERE o.sesionId = :sesionId
			   AND o.responsable = :responsable
			   AND o.deletedAt IS NULL
			""")
	Optional<Obligacion> findPorPrestacion(
			@Param("sesionId") long sesionId,
			@Param("responsable") Responsable responsable);

	@Override
	@Query("""
			SELECT o FROM Obligacion o
			 WHERE o.organizationId = :organizationId
			   AND o.personaId = :personaId
			   AND o.deletedAt IS NULL
			 ORDER BY o.devengadaEn DESC
			""")
	List<Obligacion> findDeLaPersona(
			@Param("organizationId") long organizationId,
			@Param("personaId") long personaId);
}
