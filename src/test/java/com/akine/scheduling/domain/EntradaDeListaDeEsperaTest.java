package com.akine.scheduling.domain;

import com.akine.scheduling.domain.exception.EntradaDeEsperaCerradaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Que le sirve a quien espera, y que una entrada se resuelve una sola vez (TURNO.md §20). */
class EntradaDeListaDeEsperaTest {

	private static final long OFERTA = 42L;
	private static final long PROFESIONAL = 31L;
	private static final LocalDate LUNES = LocalDate.of(2027, 3, 8);
	private static final LocalDate MARTES = LUNES.plusDays(1);
	private static final Instant AHORA = Instant.parse("2027-03-01T12:00:00Z");

	@Test
	@DisplayName("no nace con el rango de fechas al reves ni con media franja horaria: el CHECK de V126 dice lo mismo")
	void lo_que_no_se_puede_anotar() {
		assertThatThrownBy(() -> entrada(null, MARTES, LUNES, null, null, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fechas");
		assertThatThrownBy(() -> entrada(null, null, LUNES, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> entrada(null, LUNES, null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> entrada(null, LUNES, MARTES, null, LocalTime.of(9, 0), null))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("franja");
		assertThatThrownBy(() -> entrada(null, LUNES, MARTES, null, LocalTime.of(10, 0), LocalTime.of(9, 0)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("una entrada abierta admite cualquier horario de la prestacion dentro de sus fechas")
	void entrada_abierta() {
		EntradaDeListaDeEspera abierta = entrada(null, LUNES, MARTES, null, null, null);

		assertThat(abierta.admite(OFERTA, 99L, LUNES, LocalTime.of(7, 0), LocalTime.of(8, 0))).isTrue();
		assertThat(abierta.admite(OFERTA, null, MARTES, LocalTime.of(22, 0), LocalTime.of(23, 0)))
				.as("fechaHasta es inclusiva").isTrue();
		assertThat(abierta.admite(43L, null, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0))).as("otra prestacion").isFalse();
		assertThat(abierta.admite(OFERTA, null, LUNES.minusDays(1), LocalTime.of(9, 0), LocalTime.of(10, 0))).isFalse();
		assertThat(abierta.admite(OFERTA, null, MARTES.plusDays(1), LocalTime.of(9, 0), LocalTime.of(10, 0))).isFalse();
	}

	@Test
	@DisplayName("el profesional preferido, los dias y la franja recortan lo que le sirve")
	void preferencias() {
		EntradaDeListaDeEspera conPreferencias = entrada(PROFESIONAL, LUNES, LUNES.plusDays(7),
				Set.of(DayOfWeek.MONDAY), LocalTime.of(14, 0), LocalTime.of(18, 0));

		assertThat(conPreferencias.admite(OFERTA, PROFESIONAL, LUNES, LocalTime.of(14, 0), LocalTime.of(15, 0))).isTrue();
		assertThat(conPreferencias.admite(OFERTA, 99L, LUNES, LocalTime.of(14, 0), LocalTime.of(15, 0))).as("otro profesional").isFalse();
		assertThat(conPreferencias.admite(OFERTA, null, LUNES, LocalTime.of(14, 0), LocalTime.of(15, 0))).as("sin profesional").isFalse();
		assertThat(conPreferencias.admite(OFERTA, PROFESIONAL, MARTES, LocalTime.of(14, 0), LocalTime.of(15, 0))).as("otro dia").isFalse();
		assertThat(conPreferencias.admite(OFERTA, PROFESIONAL, LUNES, LocalTime.of(13, 30), LocalTime.of(14, 15)))
				.as("arranca antes de la franja").isFalse();
		assertThat(conPreferencias.admite(OFERTA, PROFESIONAL, LUNES, LocalTime.of(17, 30), LocalTime.of(18, 15)))
				.as("un turno que termina despues de las 18 no le sirve a quien pidio hasta las 18").isFalse();
	}

	@Test
	@DisplayName("los dias se guardan ordenados y se leen como se pidieron")
	void dias_de_la_semana() {
		EntradaDeListaDeEspera entrada = entrada(null, LUNES, MARTES, Set.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY), null, null);

		assertThat(entrada.dias()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
		assertThat(entrada(null, LUNES, MARTES, Set.of(), null, null).dias()).isEmpty();
	}

	@Test
	@DisplayName("asignar guarda el turno, descartar guarda el motivo, y resuelta ya no admite nada")
	void se_resuelve_una_sola_vez() {
		EntradaDeListaDeEspera asignada = entrada(null, LUNES, MARTES, null, null, null);
		asignada.asignar(300L, 9L, AHORA);
		assertThat(asignada.getEstado()).isEqualTo(EstadoListaDeEspera.ASIGNADA);
		assertThat(asignada.getTurnoId()).isEqualTo(300L);
		assertThat(asignada.admite(OFERTA, null, LUNES, LocalTime.of(9, 0), LocalTime.of(10, 0))).isFalse();
		assertThatThrownBy(() -> asignada.descartar("Otra vez", 9L, AHORA)).isInstanceOf(EntradaDeEsperaCerradaException.class);
		assertThatThrownBy(() -> asignada.asignar(301L, 9L, AHORA)).isInstanceOf(EntradaDeEsperaCerradaException.class);

		EntradaDeListaDeEspera descartada = entrada(null, LUNES, MARTES, null, null, null);
		descartada.descartar("  Ya consiguio  ", 9L, AHORA);
		assertThat(descartada.getEstado()).isEqualTo(EstadoListaDeEspera.DESCARTADA);
		assertThat(descartada.getMotivoCierre()).isEqualTo("Ya consiguio");
		assertThat(descartada.getTurnoId()).isNull();

		EntradaDeListaDeEspera sinMotivo = entrada(null, LUNES, MARTES, null, null, null);
		sinMotivo.descartar("   ", 9L, AHORA);
		assertThat(sinMotivo.getMotivoCierre()).isNull();
	}

	private static EntradaDeListaDeEspera entrada(
			Long profesional, LocalDate desde, LocalDate hasta, Set<DayOfWeek> dias, LocalTime horaDesde, LocalTime horaHasta) {

		EntradaDeListaDeEspera entrada = new EntradaDeListaDeEspera(1L, 7L, 128L, OFERTA, profesional, null, null,
				desde, hasta, dias, horaDesde, horaHasta, null, null, 9L, AHORA);
		ReflectionTestUtils.setField(entrada, "id", 900L);
		return entrada;
	}
}
