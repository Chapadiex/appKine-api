package com.akine.encounter.infrastructure;

import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.domain.ProximaConducta;
import com.akine.encounter.domain.Sesion;
import com.akine.encounter.domain.Tolerancia;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import com.akine.scheduling.spi.AtencionProbe.AtencionDeTurno;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * La costura hacia M14 que usan la agenda y el plan. El estado operativo y la cuenta de sesiones
 * dependen de que distinga una atencion abierta de una cerrada (DP-05).
 */
@ExtendWith(MockitoExtension.class)
class EncounterAtencionProbeTest {

	private static final long ORG_ID = 1L;

	@Mock private SesionRepositoryPort sesiones;

	@Test
	@DisplayName("en lote devuelve la sesion de cada turno y si ya se cerro")
	void atenciones_en_lote() {
		given(sesiones.findVivasPorTurnos(ORG_ID, List.of(301L, 302L, 303L)))
				.willReturn(List.of(abierta(301L, 501L), cerrada(302L, 502L)));

		assertThat(new EncounterAtencionProbe(sesiones).atencionesDe(ORG_ID, List.of(301L, 302L, 303L)))
				.containsOnly(
						Map.entry(301L, new AtencionDeTurno(501L, false)),
						Map.entry(302L, new AtencionDeTurno(502L, true)));
	}

	@Test
	@DisplayName("sin turnos no consulta nada: pintar una agenda vacia no pega en la base")
	void sin_turnos_no_consulta() {
		EncounterAtencionProbe sonda = new EncounterAtencionProbe(sesiones);

		assertThat(sonda.atencionesDe(ORG_ID, List.of())).isEmpty();
		assertThat(sonda.atencionesDe(ORG_ID, null)).isEmpty();
		verifyNoInteractions(sesiones);
	}

	@Test
	@DisplayName("tieneAtencion no mira el estado: una sesion cerrada sigue siendo una atencion ocurrida")
	void tiene_atencion() {
		given(sesiones.findVivaPorTurno(ORG_ID, 302L)).willReturn(Optional.of(cerrada(302L, 502L)));
		given(sesiones.findVivaPorTurno(ORG_ID, 303L)).willReturn(Optional.empty());

		EncounterAtencionProbe sonda = new EncounterAtencionProbe(sesiones);

		assertThat(sonda.tieneAtencion(ORG_ID, 7L, 302L)).isTrue();
		assertThat(sonda.tieneAtencion(ORG_ID, 7L, 303L)).isFalse();
	}

	private static Sesion abierta(long turnoId, long sesionId) {
		Sesion sesion = new Sesion(ORG_ID, 7L, 88L, null, turnoId, 42L, 31L, Instant.EPOCH, 8L);
		ReflectionTestUtils.setField(sesion, "id", sesionId);
		return sesion;
	}

	private static Sesion cerrada(long turnoId, long sesionId) {
		Sesion sesion = abierta(turnoId, sesionId);
		sesion.cerrar(new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", "alivio",
				Tolerancia.REGULAR, "hielo", ProximaConducta.REEVALUA), 8, null,
				Instant.parse("2026-09-15T12:48:00Z"), 8L);
		return sesion;
	}
}
