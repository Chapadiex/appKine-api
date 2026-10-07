package com.akine.organization.infrastructure.tenant;

import com.akine.organization.domain.PlatformRole;
import com.akine.organization.domain.port.PlatformRoleRepositoryPort;
import com.akine.organization.spi.PlatformAdminRoster;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementacion de {@link PlatformAdminRoster} sobre {@code platform_role}.
 *
 * <p>La vigencia se evalua en Java con {@link PlatformRole#isValidAt(Instant)}, igual que
 * {@link OrganizationPlatformRoleDirectory}: la regla vive en un solo lugar y no depende del reloj
 * del motor.
 */
@Component
public class OrganizationPlatformAdminRoster implements PlatformAdminRoster {

	private final PlatformRoleRepositoryPort platformRoleRepository;

	public OrganizationPlatformAdminRoster(PlatformRoleRepositoryPort platformRoleRepository) {
		this.platformRoleRepository = platformRoleRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public Set<Long> cuentasConRolDePlataforma(Instant at) {
		return platformRoleRepository.findAllByActiveTrue().stream()
				.filter(rol -> rol.isValidAt(at))
				.map(PlatformRole::getAccountId)
				.collect(Collectors.toUnmodifiableSet());
	}
}
