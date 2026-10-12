package com.akine.identity.infrastructure;

import com.akine.identity.domain.ColaboradorInvitacion;
import com.akine.identity.domain.EstadoInvitacion;
import com.akine.identity.domain.port.ColaboradorInvitacionRepositoryPort;
import com.akine.notification.spi.NotificationType;
import com.akine.notification.spi.VigenciaDeNotificacion.NotificacionEnCurso;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Cuando el correo de una invitacion deja de tener sentido (QA, PR #95). */
class VigenciaDeInvitacionTest {

	private static final Instant AHORA = Instant.parse("2026-10-12T15:00:00Z");
	private static final Instant EXPIRA = AHORA.plus(Duration.ofDays(14));
	private static final long ORG = 7L;
	private static final long ID = 42L;

	private final ColaboradorInvitacionRepositoryPort repo = mock(ColaboradorInvitacionRepositoryPort.class);
	private final VigenciaDeInvitacion vigencia = new VigenciaDeInvitacion(repo, () -> AHORA);

	private static NotificacionEnCurso correo(Instant expiraEnLaClave) {
		return new NotificacionEnCurso(NotificationType.INVITACION_COLABORADOR, ORG,
				"invitacion:" + ID + ":" + expiraEnLaClave.toEpochMilli(),
				String.valueOf(expiraEnLaClave.toEpochMilli()));
	}

	private void invitacion(EstadoInvitacion estado, Instant expiraEn) {
		ColaboradorInvitacion invitacion = mock(ColaboradorInvitacion.class);
		given(invitacion.getEstado()).willReturn(estado);
		given(invitacion.getExpiraEn()).willReturn(expiraEn);
		given(invitacion.estaVencida(AHORA)).willReturn(expiraEn.isBefore(AHORA));
		given(repo.findByIdAndOrganizationId(ID, ORG)).willReturn(Optional.of(invitacion));
	}

	@Test
	@DisplayName("pendiente, sin vencer y de la emision vigente: se envia")
	void la_vigente_sale() {
		invitacion(EstadoInvitacion.PENDIENTE, EXPIRA);

		assertThat(vigencia.motivoDeNoVigencia(correo(EXPIRA))).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(value = EstadoInvitacion.class, names = {"CANCELADA", "ACEPTADA", "RECHAZADA"})
	@DisplayName("una invitacion resuelta no se envia")
	void la_resuelta_no_sale(EstadoInvitacion estado) {
		invitacion(estado, EXPIRA);

		assertThat(vigencia.motivoDeNoVigencia(correo(EXPIRA))).get().asString()
				.startsWith("No se envia: la invitacion");
	}

	@Test
	@DisplayName("el correo de una emision reemplazada por un reenvio no se envia")
	void la_reemplazada_no_sale() {
		invitacion(EstadoInvitacion.PENDIENTE, EXPIRA.plus(Duration.ofHours(3)));

		assertThat(vigencia.motivoDeNoVigencia(correo(EXPIRA))).get().asString().contains("reenvio");
	}

	@Test
	@DisplayName("la que ya no existe en el tenant de la fila no se envia")
	void la_inexistente_no_sale() {
		given(repo.findByIdAndOrganizationId(ID, ORG)).willReturn(Optional.empty());

		assertThat(vigencia.motivoDeNoVigencia(correo(EXPIRA))).get().asString().contains("no existe");
	}

	@Test
	@DisplayName("otro tipo de notificacion no se evalua")
	void otro_tipo_no_se_mira() {
		NotificacionEnCurso activacion = new NotificacionEnCurso(
				NotificationType.ACTIVACION_CUENTA, null, "activacion:9", "9");

		assertThat(vigencia.motivoDeNoVigencia(activacion)).isEmpty();
		verifyNoInteractions(repo);
	}
}
