package com.akine.organization.infrastructure;

import com.akine.organization.domain.PlatformRole;
import com.akine.organization.domain.port.PlatformRoleRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acceso a {@code platform_role}.
 *
 * <p>Es la unica tabla del modulo sin {@code organization_id} (ADR-0020), asi que aca no hay
 * filtro por tenant que verificar — y por eso importa que el unico camino de lectura sea
 * {@code isPlatformAdmin(accountId, at)}, resuelto desde el {@code sub} del token. No existe ni
 * debe existir un listado de cuentas de plataforma alcanzable desde una ruta de tenant.
 */
public interface PlatformRoleRepository
		extends JpaRepository<PlatformRole, Long>, PlatformRoleRepositoryPort {
}
