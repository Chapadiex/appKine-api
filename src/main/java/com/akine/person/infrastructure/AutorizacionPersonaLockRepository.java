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
 *   <li>{@code INSERT ... ON DUPLICATE KEY UPDATE} es sintaxis de MySQL y no tiene equivalente en
 *       JPQL. Es lo que hace la creacion idempotente entre transacciones concurrentes, que es
 *       exactamente lo que evita el deadlock de la primera rafaga.
 * </ul>
 *
 * <p>{@code id = id} en el UPDATE es un no-op deliberado: la clausula es obligatoria, y cualquier
 * asignacion real escribiria la fila y tomaria un lock que el llamador no pidio.
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
			VALUES (:organizationId, :personaId, UTC_TIMESTAMP(6))
			ON DUPLICATE KEY UPDATE id = id
			""", nativeQuery = true)
	@Override
	void crearSiFalta(
			@Param("organizationId") long organizationId, @Param("personaId") long personaId);
}
