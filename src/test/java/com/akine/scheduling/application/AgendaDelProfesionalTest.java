package com.akine.scheduling.application;

import com.akine.organization.spi.ConsultorioMembershipDirectory;
import com.akine.organization.spi.ConsultorioMembershipSnapshot;
import com.akine.organization.spi.PermissionDecision;
import com.akine.resource.spi.BloqueoDeAgendaDeProfesional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Agenda de profesionales (checklist §1.10): con {@code turno:manage} de alcance propio, el
 * profesional opera sus turnos y recibe 403 en los ajenos. La comparacion es por CUENTA: la misma
 * persona tiene una membership por sede.
 */
class AgendaDelProfesionalTest {

	private static final long ORG_ID = 7L;
	private static final long CUENTA = 42L;
	private static final long SU_MEMBERSHIP = 100L;
	private static final long SU_MEMBERSHIP_EN_OTRA_SEDE = 101L;
	private static final long MEMBERSHIP_AJENA = 200L;

	private static final PermissionDecision PROPIA =
			PermissionDecision.concedida(PermissionDecision.ALCANCE_ACTIVIDAD_PROPIA, false);
	private static final PermissionDecision DE_SEDE = PermissionDecision.concedida("CONSULTORIO", false);

	private final ConsultorioMembershipDirectory memberships = mock(ConsultorioMembershipDirectory.class);
	private final AgendaDelProfesional agenda =
			new AgendaDelProfesional(memberships, mock(BloqueoDeAgendaDeProfesional.class));
	private final OperatingActor actor = new OperatingActor(CUENTA, false, ORG_ID, 11L, "PROFESIONAL");

	AgendaDelProfesionalTest() {
		given(memberships.find(ORG_ID, SU_MEMBERSHIP)).willReturn(Optional.of(membership(SU_MEMBERSHIP, CUENTA)));
		given(memberships.find(ORG_ID, SU_MEMBERSHIP_EN_OTRA_SEDE))
				.willReturn(Optional.of(membership(SU_MEMBERSHIP_EN_OTRA_SEDE, CUENTA)));
		given(memberships.find(ORG_ID, MEMBERSHIP_AJENA)).willReturn(Optional.of(membership(MEMBERSHIP_AJENA, 43L)));
	}

	@Test
	@DisplayName("Con alcance propio, su turno pasa, aunque sea con su membership de otra sede")
	void su_turno_pasa() {
		assertThatCode(() -> agenda.exigirPropio(PROPIA, actor, ORG_ID, SU_MEMBERSHIP, SU_MEMBERSHIP_EN_OTRA_SEDE))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("Con alcance propio, un turno ajeno es 403")
	void el_turno_ajeno_es_403() {
		assertThatThrownBy(() -> agenda.exigirPropio(PROPIA, actor, ORG_ID, MEMBERSHIP_AJENA))
				.isInstanceOf(AccessDeniedException.class);
		// Reprogramar su turno hacia la agenda de otro tambien.
		assertThatThrownBy(() -> agenda.exigirPropio(PROPIA, actor, ORG_ID, SU_MEMBERSHIP, MEMBERSHIP_AJENA))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("Con alcance propio, un turno sin profesional no es suyo: 403")
	void sin_profesional_es_403() {
		assertThatThrownBy(() -> agenda.exigirPropio(PROPIA, actor, ORG_ID, (Long) null))
				.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("Con alcance de sede (secretaria, o profesional con grant) no se recorta nada")
	void con_alcance_de_sede_no_recorta() {
		assertThatCode(() -> agenda.exigirPropio(DE_SEDE, actor, ORG_ID, MEMBERSHIP_AJENA, null))
				.doesNotThrowAnyException();
	}

	private static ConsultorioMembershipSnapshot membership(long id, long cuenta) {
		return new ConsultorioMembershipSnapshot(id, cuenta, ORG_ID, 11L, "PROFESIONAL", "ACTIVA",
				Instant.parse("2026-01-01T00:00:00Z"), null, true, true);
	}
}
