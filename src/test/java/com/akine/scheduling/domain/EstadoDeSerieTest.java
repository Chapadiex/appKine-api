package com.akine.scheduling.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La regla del estado derivado de una serie (AKINE E-8b, DP-20). Los turnos se crean en una semana
 * fija y se evaluan con un {@code ahora} movil: antes, en el medio o despues de ellos.
 */
class EstadoDeSerieTest {

	private static final Instant CREACION = Instant.parse("2026-09-01T12:00:00Z");
	private static final Instant LUNES = Instant.parse("2026-09-07T12:00:00Z");
	private static final Instant DESPUES_DE_TODO = LUNES.plus(Duration.ofDays(30));

	@Test
	@DisplayName("con un turno pendiente es VIGENTE, aunque el ultimo este cancelado")
	void vigente_manda() {
		Turno pendiente = turno(0);
		Turno ultimoCancelado = cancelado(14);

		assertThat(EstadoDeSerie.de(List.of(pendiente, ultimoCancelado), CREACION))
				.isEqualTo(EstadoDeSerie.VIGENTE);
	}

	@Test
	@DisplayName("cancelada entera: todos sus turnos cancelados es CANCELADA")
	void cancelada_entera() {
		assertThat(EstadoDeSerie.de(List.of(cancelado(0), cancelado(7), cancelado(14)), CREACION))
				.isEqualTo(EstadoDeSerie.CANCELADA);
		assertThat(EstadoDeSerie.de(List.of(cancelado(0), cancelado(7), cancelado(14)), DESPUES_DE_TODO))
				.isEqualTo(EstadoDeSerie.CANCELADA);
	}

	@Test
	@DisplayName("cancelada desde la mitad con atenciones previas: CANCELADA una vez pasadas las atenciones")
	void cancelada_desde_la_mitad() {
		Turno atendido = turno(0);
		List<Turno> serie = List.of(atendido, cancelado(7), cancelado(14));

		assertThat(EstadoDeSerie.de(serie, CREACION)).isEqualTo(EstadoDeSerie.VIGENTE);
		assertThat(EstadoDeSerie.de(serie, LUNES.plus(Duration.ofDays(1)))).isEqualTo(EstadoDeSerie.CANCELADA);
	}

	@Test
	@DisplayName("terminada por fecha: el ultimo turno paso, aunque haya cancelados en el medio, es FINALIZADA")
	void finalizada_por_fecha() {
		assertThat(EstadoDeSerie.de(List.of(turno(0), turno(7), turno(14)), DESPUES_DE_TODO))
				.isEqualTo(EstadoDeSerie.FINALIZADA);
		assertThat(EstadoDeSerie.de(List.of(turno(0), cancelado(7), turno(14)), DESPUES_DE_TODO))
				.isEqualTo(EstadoDeSerie.FINALIZADA);
	}

	@Test
	@DisplayName("un ultimo turno AUSENTE no es una cancelacion: FINALIZADA")
	void ultimo_ausente() {
		Turno ausente = turno(14);
		ausente.marcarAusente(DESPUES_DE_TODO);

		assertThat(EstadoDeSerie.de(List.of(cancelado(0), cancelado(7), ausente), DESPUES_DE_TODO))
				.isEqualTo(EstadoDeSerie.FINALIZADA);
	}

	@Test
	@DisplayName("empate en el instante entre un cancelado y uno no cancelado: FINALIZADA, igual que el JPQL (>=)")
	void empate_es_finalizada() {
		assertThat(EstadoDeSerie.de(List.of(turno(7), cancelado(7)), DESPUES_DE_TODO))
				.isEqualTo(EstadoDeSerie.FINALIZADA);
	}

	@Test
	@DisplayName("sin turnos es FINALIZADA")
	void sin_turnos() {
		assertThat(EstadoDeSerie.de(List.of(), CREACION)).isEqualTo(EstadoDeSerie.FINALIZADA);
	}

	private static Turno cancelado(int diasDesdeElLunes) {
		Turno turno = turno(diasDesdeElLunes);
		turno.cancelar("Baja del tratamiento", 7L, CREACION);
		return turno;
	}

	private static Turno turno(int diasDesdeElLunes) {
		Instant inicio = LUNES.plus(Duration.ofDays(diasDesdeElLunes));
		return new Turno(1L, 7L, 42L, 128L, 31L, null,
				inicio, inicio.plus(Duration.ofMinutes(45)), 7L, CREACION, null, null);
	}
}
