package com.akine.scheduling.application;

import com.akine.scheduling.domain.AlcanceDeSerie;
import com.akine.scheduling.domain.MotivoDeOmision;
import com.akine.scheduling.domain.Turno;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Que toca una operacion de serie (DP-04: solo turnos futuros pendientes) y en que orden se mueven.
 */
@DisplayName("SeleccionDeAlcance")
class SeleccionDeAlcanceTest {

	private static final Instant AHORA = Instant.parse("2027-03-10T12:00:00Z");
	private static final Duration SEMANA = Duration.ofDays(7);

	/** Cinco lunes: uno pasado y cuatro futuros. */
	private final Turno pasado = turno(1L, AHORA.minus(SEMANA));
	private final Turno primero = turno(2L, AHORA.plus(Duration.ofDays(5)));
	private final Turno segundo = turno(3L, primero.getInicio().plus(SEMANA));
	private final Turno tercero = turno(4L, segundo.getInicio().plus(SEMANA));
	private final Turno cuarto = turno(5L, tercero.getInicio().plus(SEMANA));
	private final List<Turno> serie = List.of(pasado, primero, segundo, tercero, cuarto);

	@Test
	@DisplayName("ESTE_Y_SIGUIENTES: el pivote y los que empiezan despues, nunca los anteriores")
	void este_y_siguientes() {
		SeleccionDeAlcance seleccion = SeleccionDeAlcance.calcular(
				serie, AlcanceDeSerie.ESTE_Y_SIGUIENTES, segundo, AHORA, turno -> false, turno -> false);

		assertThat(seleccion.afectados()).containsExactly(segundo, tercero, cuarto);
		assertThat(seleccion.omitidos()).isEmpty();
	}

	@Test
	@DisplayName("TODA_LA_SERIE omite el pasado, el cancelado y el que tiene atencion")
	void toda_la_serie_solo_toca_pendientes() {
		primero.cancelar("Aviso", 9L, AHORA);

		SeleccionDeAlcance seleccion = SeleccionDeAlcance.calcular(
				serie, AlcanceDeSerie.TODA_LA_SERIE, null, AHORA, turno -> turno == cuarto, turno -> false);

		assertThat(seleccion.afectados()).containsExactly(segundo, tercero);
		assertThat(seleccion.omitidos())
				.extracting(SeleccionDeAlcance.Omitido::motivo)
				.containsExactly(MotivoDeOmision.YA_EMPEZO, MotivoDeOmision.ESTADO_TERMINAL,
						MotivoDeOmision.CON_ATENCION);
	}

	@Test
	@DisplayName("un paciente que ya llego (recepcion abierta) no se cancela por lote")
	void en_espera_se_omite() {
		// Desde E-4 (DP-16) la llegada es una recepcion abierta, no un estado del turno.

		SeleccionDeAlcance seleccion = SeleccionDeAlcance.calcular(
				serie, AlcanceDeSerie.ESTE, primero, AHORA, turno -> false, turno -> turno == primero);

		assertThat(seleccion.afectados()).isEmpty();
		assertThat(seleccion.omitidos()).extracting(SeleccionDeAlcance.Omitido::motivo)
				.containsExactly(MotivoDeOmision.EN_ESPERA);
	}

	@Test
	@DisplayName("hacia adelante se mueve primero el ultimo, para no chocar contra el hermano siguiente")
	void orden_de_proceso() {
		List<Turno> afectados = List.of(segundo, tercero, cuarto);

		assertThat(SerieDeTurnosService.ordenDeProceso(afectados, SEMANA))
				.containsExactly(cuarto, tercero, segundo);
		assertThat(SerieDeTurnosService.ordenDeProceso(afectados, SEMANA.negated()))
				.containsExactly(segundo, tercero, cuarto);
	}

	private static Turno turno(long id, Instant inicio) {
		Turno turno = new Turno(1L, 7L, 42L, 128L, 31L, null, inicio, inicio.plus(Duration.ofHours(1)),
				9L, inicio.minus(Duration.ofDays(30)), null, null);
		ReflectionTestUtils.setField(turno, "id", id);
		return turno;
	}

}
