package com.akine.scheduling.infrastructure;

import com.akine.person.spi.AporteDeResumen;
import com.akine.person.spi.ConsultaDeResumen;
import com.akine.person.spi.IndicadorDeResumen;
import com.akine.scheduling.domain.EstadoTurno;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * Lo que {@code scheduling} aporta al Paciente 360.
 *
 * <p>El caso que vale la pena es el conteo: <b>"turnos futuros" cuenta sobre toda la lectura, no
 * sobre los hitos recortados</b>. Si alguna vez se recorta la consulta al mismo limite que los
 * hitos, el numero pasa a ser "3 de los ultimos 5" presentado como si fuera exacto, y este test
 * lo agarra.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TurnosEnElResumenDePersona")
class TurnosEnElResumenDePersonaTest {

	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;

	@Mock
	private TurnoRepositoryPort turnos;

	@Test
	@DisplayName("declara turno:read, que es el permiso con el que se lee la agenda")
	void declara_su_permiso() {
		assertThat(new TurnosEnElResumenDePersona(turnos).permisoRequerido())
				.isEqualTo("turno:read");
		assertThat(new TurnosEnElResumenDePersona(turnos).seccion()).isEqualTo("turnos");
	}

	@Test
	@DisplayName("cuenta futuros y ausencias sobre TODO lo leido, y recorta solo los hitos")
	void cuenta_sobre_todo_y_recorta_los_hitos() {
		Instant ahora = Instant.now();
		given(turnos.findDeLaPersona(anyLong(), anyLong(), anyInt())).willReturn(List.of(
				turno(1L, ahora.plus(Duration.ofDays(3)), EstadoTurno.RESERVADO),
				turno(2L, ahora.plus(Duration.ofDays(2)), EstadoTurno.CONFIRMADO),
				turno(3L, ahora.plus(Duration.ofDays(1)), EstadoTurno.RESERVADO),
				turno(4L, ahora.minus(Duration.ofDays(1)), EstadoTurno.AUSENTE),
				turno(5L, ahora.minus(Duration.ofDays(2)), EstadoTurno.AUSENTE),
				turno(6L, ahora.minus(Duration.ofDays(3)), EstadoTurno.CANCELADO)));

		AporteDeResumen aporte = new TurnosEnElResumenDePersona(turnos)
				.aportar(new ConsultaDeResumen(ORG_ID, SEDE_ID, PERSONA_ID, 2));

		assertThat(aporte.hitos()).hasSize(2);
		assertThat(indicador(aporte, "turnos-futuros").cantidad()).isEqualTo(3L);
		assertThat(indicador(aporte, "turnos-ausencias").cantidad()).isEqualTo(2L);
	}

	@Test
	@DisplayName("un turno cancelado no cuenta como futuro aunque su fecha lo sea")
	void el_cancelado_no_es_futuro() {
		Instant ahora = Instant.now();
		given(turnos.findDeLaPersona(anyLong(), anyLong(), anyInt())).willReturn(List.of(
				turno(1L, ahora.plus(Duration.ofDays(1)), EstadoTurno.CANCELADO)));

		AporteDeResumen aporte = new TurnosEnElResumenDePersona(turnos)
				.aportar(new ConsultaDeResumen(ORG_ID, SEDE_ID, PERSONA_ID, 5));

		assertThat(indicador(aporte, "turnos-futuros").cantidad()).isZero();
		assertThat(aporte.hitos()).hasSize(1);
	}

	@Test
	@DisplayName("una persona sin turnos produce un aporte con ceros, no una seccion ausente")
	void sin_turnos_hay_aporte() {
		given(turnos.findDeLaPersona(anyLong(), anyLong(), anyInt())).willReturn(List.of());

		AporteDeResumen aporte = new TurnosEnElResumenDePersona(turnos)
				.aportar(new ConsultaDeResumen(ORG_ID, SEDE_ID, PERSONA_ID, 5));

		assertThat(aporte.hitos()).isEmpty();
		assertThat(indicador(aporte, "turnos-futuros").cantidad()).isZero();
	}

	private static IndicadorDeResumen indicador(AporteDeResumen aporte, String clave) {
		return aporte.indicadores().stream()
				.filter(i -> i.clave().equals(clave))
				.findFirst()
				.orElseThrow();
	}

	private static Turno turno(long id, Instant inicio, EstadoTurno estado) {
		Turno turno = new Turno(ORG_ID, SEDE_ID, 1L, PERSONA_ID, 2L, 3L,
				inicio, inicio.plus(Duration.ofMinutes(30)), 4L, Instant.now(), null, null);
		ReflectionTestUtils.setField(turno, "id", id);
		ReflectionTestUtils.setField(turno, "estado", estado);
		if (estado == EstadoTurno.CANCELADO) {
			ReflectionTestUtils.setField(turno, "deletedAt", Instant.now());
		}
		return turno;
	}
}
