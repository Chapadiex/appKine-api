package com.akine.organization.infrastructure;

import com.akine.organization.domain.OrganizationMarca;
import com.akine.organization.domain.port.OrganizationMarcaRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Acceso a {@code organization_marca}, propiedad del modulo {@code organization}. */
public interface OrganizationMarcaRepository
		extends JpaRepository<OrganizationMarca, Long>, OrganizationMarcaRepositoryPort {

	@Override
	@Modifying
	@Query(value = """
			INSERT INTO organization_marca (organization_id, version, created_at, updated_at)
			VALUES (:organizationId, 0, now(), now())
			ON CONFLICT (organization_id) DO NOTHING""", nativeQuery = true)
	void asegurarFila(@Param("organizationId") Long organizationId);
}
