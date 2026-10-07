package com.akine.organization.infrastructure.tenant;

import com.akine.organization.domain.PlatformRole;
import com.akine.organization.domain.port.PlatformRoleRepositoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class OrganizationPlatformAdminRosterTest {

	private static final Instant AHORA = Instant.parse("2026-10-07T12:00:00Z");

	@Test
	@DisplayName("devuelve solo las cuentas con rol activo y vigente en ese instante")
	void solo_vigentes() {
		PlatformRoleRepositoryPort repo = mock(PlatformRoleRepositoryPort.class);
		PlatformRole vigente = new PlatformRole(1L, null, "seed", AHORA.minus(1, ChronoUnit.DAYS));
		PlatformRole futuro = new PlatformRole(2L, 1L, "todavia no", AHORA.plus(1, ChronoUnit.DAYS));
		given(repo.findAllByActiveTrue()).willReturn(List.of(vigente, futuro));

		assertThat(new OrganizationPlatformAdminRoster(repo).cuentasConRolDePlataforma(AHORA))
				.containsExactly(1L);
	}
}
