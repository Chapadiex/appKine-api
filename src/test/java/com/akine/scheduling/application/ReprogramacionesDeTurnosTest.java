package com.akine.scheduling.application;

import com.akine.scheduling.domain.TipoEventoTurno;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.TurnoEvento;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** El indicador "reprogramado": derivado del historial, siguiendo la cadena de traslados. */
class ReprogramacionesDeTurnosTest {

	private static final Instant NUEVE = Instant.parse("2027-03-08T12:00:00Z");
	private static final Instant DIEZ = Instant.parse("2027-03-08T13:00:00Z");
	private static final Instant ONCE = Instant.parse("2027-03-08T14:00:00Z");

	@Test
	@DisplayName("un turno que nadie movio no es reprogramado")
	void sin_movimientos() {
		Turno turno = turno(1L, null, NUEVE);
		TurnoEvento reserva = evento(1L, TipoEventoTurno.RESERVA, null, "2026-11-01T10:00:00Z", 5L, null);

		assertThat(ReprogramacionesDeTurnos.calcular(List.of(turno), Map.of(1L, List.of(reserva)))).isNull();
	}

	@Test
	@DisplayName("reprogramado en el original, trasladado y vuelto a reprogramar: cuenta tres, el horario "
			+ "original es el de la primera reserva y la ultima es la mas nueva")
	void cadena_de_traslado() {
		Turno original = turno(1L, null, DIEZ);
		Turno trasladado = turno(2L, 1L, ONCE);
		TurnoEvento reprogramacionOriginal = evento(1L, TipoEventoTurno.REPROGRAMACION, NUEVE,
				"2026-11-01T10:00:00Z", 5L, "Licencia");
		TurnoEvento reservaPorTraslado = evento(2L, TipoEventoTurno.RESERVA, null,
				"2026-11-02T10:00:00Z", 6L, "Cambio de sede");
		TurnoEvento reprogramacionNueva = evento(2L, TipoEventoTurno.REPROGRAMACION, ONCE,
				"2026-11-03T10:00:00Z", 7L, "Pidio mas tarde");

		ReprogramacionDeTurnoView vista = ReprogramacionesDeTurnos.calcular(List.of(trasladado, original), Map.of(
				1L, List.of(reprogramacionOriginal),
				2L, List.of(reservaPorTraslado, reprogramacionNueva)));

		assertThat(vista.cantidad()).isEqualTo(3);
		assertThat(vista.inicioOriginal()).isEqualTo(NUEVE);
		assertThat(vista.ultimoTipo()).isEqualTo("REPROGRAMACION");
		assertThat(vista.ultimoMotivo()).isEqualTo("Pidio mas tarde");
		assertThat(vista.ultimoActorCuentaId()).isEqualTo(7L);
	}

	@Test
	@DisplayName("un traslado solo: cuenta uno, la ultima es el traslado y el horario original es el del turno de origen")
	void solo_traslado() {
		Turno original = turno(1L, null, DIEZ);
		Turno trasladado = turno(2L, 1L, ONCE);
		TurnoEvento reserva = evento(2L, TipoEventoTurno.RESERVA, null, "2026-11-02T10:00:00Z", 6L, "Cambio de sede");

		ReprogramacionDeTurnoView vista = ReprogramacionesDeTurnos.calcular(List.of(trasladado, original),
				Map.of(2L, List.of(reserva)));

		assertThat(vista.cantidad()).isEqualTo(1);
		assertThat(vista.ultimoTipo()).isEqualTo("TRASLADO");
		assertThat(vista.inicioOriginal()).isEqualTo(DIEZ);
	}

	private static Turno turno(long id, Long origen, Instant inicio) {
		Turno turno = mock(Turno.class);
		given(turno.getId()).willReturn(id);
		given(turno.getTurnoOrigenId()).willReturn(origen);
		given(turno.getInicio()).willReturn(inicio);
		return turno;
	}

	private static TurnoEvento evento(long turnoId, TipoEventoTurno tipo, Instant inicioAnterior, String en,
			Long actor, String motivo) {
		TurnoEvento evento = mock(TurnoEvento.class);
		given(evento.getTurnoId()).willReturn(turnoId);
		given(evento.getTipo()).willReturn(tipo);
		given(evento.getInicioAnterior()).willReturn(inicioAnterior);
		given(evento.getOcurridoEn()).willReturn(Instant.parse(en));
		given(evento.getActorCuentaId()).willReturn(actor);
		given(evento.getMotivo()).willReturn(motivo);
		return evento;
	}
}
