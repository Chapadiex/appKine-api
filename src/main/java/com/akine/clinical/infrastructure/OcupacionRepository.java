package com.akine.clinical.infrastructure;

import com.akine.clinical.domain.Ocupacion;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.OcupacionRepositoryPort;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Persistencia de {@link Ocupacion}. Nativas para filtrar por la columna generada
 * {@code owner_key}, nunca por {@code organization_id} (ADR-0021): asi las globales entran con
 * {@code owner_key = 0}.
 */
public interface OcupacionRepository extends JpaRepository<Ocupacion, Long>, OcupacionRepositoryPort {

	@Override
	@Query(value = """
			SELECT * FROM ocupacion
			 WHERE owner_key IN (0, :organizationId) AND active
			 ORDER BY nombre, id
			""", nativeQuery = true)
	List<Ocupacion> visibles(@Param("organizationId") long organizationId);

	@Override
	@Query(value = """
			SELECT * FROM ocupacion
			 WHERE owner_key IN (0, :organizationId) AND id = :id AND active
			""", nativeQuery = true)
	Optional<Ocupacion> buscarVisible(@Param("organizationId") long organizationId, @Param("id") long id);

	/** La global gana si existe en los dos duenos: ORDER BY owner_key. */
	@Override
	@Query(value = """
			SELECT * FROM ocupacion
			 WHERE owner_key IN (0, :organizationId) AND nombre_normalizado = :normalizado AND active
			 ORDER BY owner_key
			 LIMIT 1
			""", nativeQuery = true)
	Optional<Ocupacion> buscarVisiblePorNormalizado(
			@Param("organizationId") long organizationId, @Param("normalizado") String normalizado);
}
