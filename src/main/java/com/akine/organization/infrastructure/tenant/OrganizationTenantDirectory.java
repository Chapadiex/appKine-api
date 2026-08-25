package com.akine.organization.infrastructure.tenant;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.akine.organization.domain.port.OrganizationRepositoryPort;
import com.akine.organization.spi.OrganizationDirectory;
import com.akine.organization.spi.OrganizationSnapshot;

/**
 * Implementacion de {@link OrganizationDirectory}.
 *
 * <p>Vive junto a los otros directorios del borde y sigue su regla: <b>nunca devuelve
 * entities</b>, solo el record del {@code spi}.
 *
 * <p><b>Devuelve tambien las organizaciones dadas de baja</b>, con {@code active = false}, en
 * vez de tratarlas como inexistentes. Quien pregunta desde afuera necesita distinguir "no
 * existe" de "existe y esta dada de baja": son dos respuestas distintas para el usuario y
 * fusionarlas aca le quita la informacion a quien decide.
 */
@Component
public class OrganizationTenantDirectory implements OrganizationDirectory {

	private final OrganizationRepositoryPort organizationRepository;

	public OrganizationTenantDirectory(OrganizationRepositoryPort organizationRepository) {
		this.organizationRepository = organizationRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<OrganizationSnapshot> find(long organizationId) {
		return organizationRepository.findById(organizationId)
				.map(organizacion -> new OrganizationSnapshot(
						organizacion.getId(), organizacion.getName(), organizacion.isActive()));
	}
}
