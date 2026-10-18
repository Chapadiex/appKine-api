package com.akine.organization.infrastructure;

import com.akine.organization.domain.AccountActiveContext;
import com.akine.organization.domain.port.AccountActiveContextRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Acceso al puntero de contexto activo de una cuenta.
 *
 * <p>Hay a lo sumo una fila por cuenta ({@code uk_active_context_account}), asi que la
 * seleccion es un upsert: leer, y crear o reapuntar. Un PUT repetido con el mismo valor no
 * duplica filas ni genera un evento de auditoria nuevo.
 *
 * <p>Lo que devuelve esta consulta es una PREFERENCIA, no una autorizacion. Quien la lea debe
 * revalidarla: el puntero puede haber quedado apuntando a una membership revocada o a una
 * organizacion cancelada, y en ese caso se trata como "sin seleccion previa".
 */
public interface AccountActiveContextRepository extends JpaRepository<AccountActiveContext, Long>, AccountActiveContextRepositoryPort {

	Optional<AccountActiveContext> findByAccountId(Long accountId);

	@Modifying
	@Query(value = """
			INSERT INTO account_active_context (account_id, organization_id, consultorio_id,
			                                    updated_at)
			VALUES (:accountId, :organizationId, :consultorioId, UTC_TIMESTAMP(6))
			ON DUPLICATE KEY UPDATE organization_id = VALUES(organization_id),
			                        consultorio_id = VALUES(consultorio_id),
			                        updated_at = VALUES(updated_at)
			""", nativeQuery = true)
	@Override
	void apuntar(
			@Param("accountId") long accountId,
			@Param("organizationId") long organizationId,
			@Param("consultorioId") long consultorioId);
}
