package com.akine.scheduling.infrastructure;

import com.akine.notification.spi.NotificationType;
import com.akine.notification.spi.VigenciaDeNotificacion.NotificacionEnCurso;
import com.akine.scheduling.domain.EstadoTurno;
import com.akine.scheduling.domain.Turno;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** El aviso de reserva o reprogramacion de un turno que se cancelo mientras esperaba no sale. */
class VigenciaDeAvisoDeTurnoTest {

	private static final long ORG = 3L;

	private final TurnoRepository turnos = mock(TurnoRepository.class);
	private final VigenciaDeAvisoDeTurno vigencia = new VigenciaDeAvisoDeTurno(turnos);

	private void turno(long id, long org, EstadoTurno estado) {
		Turno turno = mock(Turno.class);
		given(turno.getOrganizationId()).willReturn(org);
		given(turno.getEstado()).willReturn(estado);
		given(turnos.findById(id)).willReturn(Optional.of(turno));
	}

	private static NotificacionEnCurso aviso(NotificationType tipo, String clave) {
		return new NotificacionEnCurso(tipo, ORG, clave, null);
	}

	@Test
	@DisplayName("la reserva de un turno vigente sale")
	void reserva_vigente() {
		turno(10L, ORG, EstadoTurno.RESERVADO);

		assertThat(vigencia.motivoDeNoVigencia(
				aviso(NotificationType.TURNO_RESERVADO, "turno-reservado:10"))).isEmpty();
	}

	@Test
	@DisplayName("la reprogramacion de un turno que despues se cancelo no sale")
	void reprogramacion_de_un_cancelado() {
		turno(11L, ORG, EstadoTurno.CANCELADO);

		assertThat(vigencia.motivoDeNoVigencia(
				aviso(NotificationType.TURNO_REPROGRAMADO, "turno-reprogramado:11:v3")))
				.get().asString().contains("se cancelo");
	}

	@Test
	@DisplayName("un turno de otro tenant cuenta como inexistente")
	void otro_tenant() {
		turno(12L, 99L, EstadoTurno.RESERVADO);

		assertThat(vigencia.motivoDeNoVigencia(
				aviso(NotificationType.TURNO_RESERVADO, "turno-reservado:12")))
				.get().asString().contains("no existe");
	}

	@Test
	@DisplayName("el aviso de cancelacion no se revalida")
	void cancelacion_no_se_mira() {
		assertThat(vigencia.motivoDeNoVigencia(
				aviso(NotificationType.TURNO_CANCELADO, "turno-cancelado:13"))).isEmpty();
		verifyNoInteractions(turnos);
	}
}
