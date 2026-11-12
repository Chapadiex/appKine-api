package com.akine.person.infrastructure;

import com.akine.person.domain.AutorizacionPersonaLock;
import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionPersonaLockRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * La fila-lock que serializa las aprobaciones de autorizaciones de un paciente.
 *
 * <p>Las dos consultas son <b>nativas</b> y ninguna de las dos se puede expresar en JPQL:
 *
 * <ul>
 *   <li>{@code FOR UPDATE} tiene equivalente en JPA ({@code PESSIMISTIC_WRITE}), pero el
 *       {@code SELECT} nativo evita que Hibernate cargue la entidad en la sesion de persistencia:
 *       la fila no se lee para usarla, se lee para bloquearla.
 *   <li>{@code INSERT ... ON CONFLICT DO NOTHING} es sintaxis de PostgreSQL y no tiene equivalente en
 *       JPQL. Es lo que hace la creacion idempotente entre transacciones concurrentes, que es
 *       exactamente lo que evita el deadlock de la primera rafaga.
 * </ul>
 *
 * <p>{@code DO NOTHING} y no {@code DO UPDATE}: una asignacion escribiria la fila y tomaria un lock
 * que el llamador no pidio.
 */
public interface AutorizacionPersonaLockRepository
		extends JpaRepository<AutorizacionPersonaLock, Long>,
		AutorizacionPersonaLockRepositoryPort {

	@Query(value = """
			SELECT * FROM autorizacion_persona_lock
			 WHERE organization_id = :organizationId
			   AND persona_id = :personaId
			 FOR UPDATE
			""", nativeQuery = true)
	@Override
	Optional<AutorizacionPersonaLock> lockByScope(
			@Param("organizationId") long organizationId, @Param("personaId") long personaId);

	@Modifying
	@Query(value = """
			INSERT INTO autorizacion_persona_lock (organization_id, persona_id, created_at)
			VALUES (:organizationId, :personaId, CURRENT_TIMESTAMP)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	@Override
	void crearSiFalta(
			@Param("organizationId") long organizationId, @Param("personaId") long personaId);

	@Query(value = """
			SELECT COUNT(*) FROM autorizacion_persona_lock
			WHERE organization_id = :organizationId
			  AND persona_id = :personaId
			""", nativeQuery = true)
	long contarFila(
			@Param("organizationId") long organizationId,
			@Param("personaId") long personaId);

	@Override
	default boolean existeFila(long organizationId, long personaId) {
		return contarFila(organizationId, personaId) > 0;
	}
}
