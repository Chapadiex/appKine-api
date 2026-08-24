package com.akine.organization.domain.port;

import com.akine.organization.domain.SupportAccess;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a {@code support_access}: los permisos temporales con los que un {@code PLATFORM_ADMIN}
 * entra a un tenant.
 *
 * <p>Se consulta en cada operacion que un administrador de plataforma intenta dentro de una
 * organizacion, asi que el filtro por {@code (organization_id, account_id)} tiene que ser un
 * seek: para eso esta {@code ix_support_access_org_account}.
 */
public interface SupportAccessRepositoryPort {

	SupportAccess save(SupportAccess access);

	/** Accesos activos de una cuenta sobre un tenant. El llamador evalua la vigencia. */
	List<SupportAccess> findAllByOrganizationIdAndAccountIdAndActiveTrue(
			Long organizationId, Long accountId);

	/** Accesos activos sobre un tenant, de cualquier administrador de plataforma. */
	List<SupportAccess> findAllByOrganizationIdAndActiveTrue(Long organizationId);

	Optional<SupportAccess> findByIdAndActiveTrue(Long id);
}
