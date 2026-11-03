package com.akine.resource.application;

import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.organization.spi.PermissionGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Agenda de profesionales (checklist §1.7, §1.8, §14): con {@code agenda:horario} o
 * {@code agenda:excepciones} de alcance propio, el profesional gestiona su horario y sus
 * excepciones; las de otro profesional y las de la sede entera son 403.
 */
class AgendaPropiaEnResourceTest {

	private static final long ORG_ID = 7L;
	private static final long SEDE = 11L;
	private static final long CUENTA = 42L;
	private static final long SU_MEMBERSHIP = 100L;
	private static final long MEMBERSHIP_AJENA = 200L;

	private final PermissionGuard guard = mock(PermissionGuard.class);
	private final ConsultorioMembershipDirectory memberships = mock(ConsultorioMembershipDirectory.class);
	private final OperatingActor actor = new OperatingActor(CUENTA, false, ORG_ID, SEDE, "PROFESIONAL");

	AgendaPropiaEnResourceTest() {
		given(memberships.find(ORG_ID, SU_MEMBERSHIP)).willReturn(Optional.of(membership(SU_MEMBERSHIP, CUENTA)));
		given(memberships.find(ORG_ID, MEMBERSHIP_AJENA)).willReturn(Optional.of(membership(MEMBERSHIP_AJENA, 43L)));
	}

	@Test
	@DisplayName("Alcance propio: su agenda pasa, la ajena y la de sede son 403")
	void alcance_propio() {
		given(guard.requirePermission(any())).willReturn(
				PermissionDecision.concedida(PermissionDecision.ALCANCE_ACTIVIDAD_PROPIA, false));

		assertThatCode(() -> exigir(SU_MEMBERSHIP)).doesNotThrowAnyException();
		assertThatThrownBy(() -> exigir(MEMBERSHIP_AJENA)).isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> exigir(null)).isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("Alcance de sede (secretaria con agenda:excepciones): ajena y de sede pasan")
	void alcance_de_sede() {
		given(guard.requirePermission(any())).willReturn(PermissionDecision.concedida("CONSULTORIO", false));

		assertThatCode(() -> exigir(MEMBERSHIP_AJENA)).doesNotThrowAnyException();
		assertThatCode(() -> exigir(null)).doesNotThrowAnyException();
	}

	private void exigir(Long membershipId) {
		AutorizacionDeSede.exigirPermisoSobreProfesional(guard, memberships, actor,
				"agenda:excepciones", ORG_ID, SEDE, membershipId);
	}

	private static ConsultorioMembershipSnapshot membership(long id, long cuenta) {
		return new ConsultorioMembershipSnapshot(id, cuenta, ORG_ID, SEDE, "PROFESIONAL", "ACTIVA",
				Instant.parse("2026-01-01T00:00:00Z"), null, true, true);
	}
}
