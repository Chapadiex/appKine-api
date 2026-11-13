package com.akine.resource.infrastructure;

import com.akine.resource.domain.AgendaProfesional;
import com.akine.resource.domain.port.AgendaProfesionalRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AgendaProfesionalRepository
		extends JpaRepository<AgendaProfesional, Long>, AgendaProfesionalRepositoryPort {

	@Query(value = """
			SELECT id FROM agenda_profesional
			 WHERE organization_id = :organizationId
			   AND account_id = :accountId
			 FOR UPDATE
			""", nativeQuery = true)
	Optional<Long> lockByScope(
			@Param("organizationId") long organizationId,
			@Param("accountId") long accountId);

	@Modifying
	@Query(value = """
			INSERT INTO agenda_profesional (organization_id, account_id, created_at)
			VALUES (:organizationId, :accountId, CURRENT_TIMESTAMP)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	@Override
	void crearSiFalta(
			@Param("organizationId") long organizationId,
			@Param("accountId") long accountId);

	@Query(value = """
			SELECT COUNT(*) FROM agenda_profesional
			 WHERE organization_id = :organizationId
			   AND account_id = :accountId
			""", nativeQuery = true)
	long contarFila(
			@Param("organizationId") long organizationId,
			@Param("accountId") long accountId);

	@Override
	default boolean existeFila(long organizationId, long accountId) {
		return contarFila(organizationId, accountId) > 0;
	}

	@Override
	default boolean bloquear(long organizationId, long accountId) {
		return lockByScope(organizationId, accountId).isPresent();
	}
}
