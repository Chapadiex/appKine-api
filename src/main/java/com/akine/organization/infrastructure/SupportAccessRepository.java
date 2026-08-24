package com.akine.organization.infrastructure;

import com.akine.organization.domain.SupportAccess;
import com.akine.organization.domain.port.SupportAccessRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Acceso a {@code support_access}.
 *
 * <p>Todas las consultas parten de {@code organizationId}: un acceso de soporte es siempre a UN
 * tenant, y la pregunta "que accesos hay" sin tenant no se hace desde ninguna ruta de negocio.
 */
public interface SupportAccessRepository
		extends JpaRepository<SupportAccess, Long>, SupportAccessRepositoryPort {
}
