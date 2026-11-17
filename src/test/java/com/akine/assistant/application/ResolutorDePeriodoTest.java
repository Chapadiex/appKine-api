package com.akine.assistant.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ResolutorDePeriodoTest {

	/** Domingo: el ultimo dia de la semana, que es donde se equivoca una semana mal anclada. */
	private static final LocalDate HOY = LocalDate.of(2026, 11, 15);

	@ParameterizedTest
	@CsvSource({
			"HOY,            2026-11-15, 2026-11-15",
			"AYER,           2026-11-14, 2026-11-14",
			"MANANA,         2026-11-16, 2026-11-16",
			"ESTA_SEMANA,    2026-11-09, 2026-11-15",
			"SEMANA_PASADA,  2026-11-02, 2026-11-08",
			"ESTE_MES,       2026-11-01, 2026-11-30",
			"MES_PASADO,     2026-10-01, 2026-10-31",
			"MES:OCTUBRE,    2026-10-01, 2026-10-31",
			"MES:DICIEMBRE,  2025-12-01, 2025-12-31",
			"MES:FEBRERO,    2026-02-01, 2026-02-28"})
	void resuelve_el_vocabulario_con_el_hoy_de_la_organizacion(String periodo, LocalDate desde, LocalDate hasta) {
		assertThat(ResolutorDePeriodo.resolver(periodo, "", HOY, false)).contains(new RangoDeDias(desde, hasta));
	}

	@ParameterizedTest
	@CsvSource({
			"DIA:LUNES,   false, 2026-11-09",
			"DIA:LUNES,   true,  2026-11-16",
			"DIA:DOMINGO, false, 2026-11-15",
			"DIA:DOMINGO, true,  2026-11-15",
			"DIA:JUEVES,  true,  2026-11-19"})
	void un_dia_de_la_semana_va_hacia_atras_o_hacia_adelante_y_hoy_cuenta(String periodo, boolean adelante, LocalDate dia) {
		assertThat(ResolutorDePeriodo.resolver(periodo, "", HOY, adelante)).contains(RangoDeDias.delDia(dia));
	}

	@Test
	void la_semana_y_el_mes_cruzan_el_anio() {
		assertThat(ResolutorDePeriodo.resolver("ESTA_SEMANA", "", LocalDate.of(2026, 1, 1), false))
				.contains(new RangoDeDias(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 4)));
		assertThat(ResolutorDePeriodo.resolver("MES_PASADO", "", LocalDate.of(2026, 1, 5), false))
				.contains(new RangoDeDias(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31)));
	}

	@ParameterizedTest
	@CsvSource({
			"MES:PASADO,     MES_PASADO",
			"semana:pasada,  SEMANA_PASADA",
			"DIA:MAÑANA,     MANANA",
			"dia:miércoles,  DIA:MIERCOLES",
			"MES:SETIEMBRE,  MES:SEPTIEMBRE",
			"esta semana,    ESTA_SEMANA",
			"FECHA:2023-11-20, FECHA:2023-11-20"})
	void normaliza_los_alias_que_escribe_el_modelo(String crudo, String esperado) {
		assertThat(ResolutorDePeriodo.normalizar(crudo)).isEqualTo(esperado);
	}

	@ParameterizedTest
	@CsvSource(value = {"null", "''", "'la semana que viene'", "DIA:FERIADO", "MES:13", "FECHA:2026-02-31"}, nullValues = "null")
	void lo_que_no_es_del_vocabulario_es_sin_periodo(String crudo) {
		assertThat(ResolutorDePeriodo.normalizar(crudo)).isNull();
	}

	@Test
	void el_anio_de_una_fecha_del_modelo_no_se_usa() {
		assertThat(ResolutorDePeriodo.resolver("FECHA:2023-11-20", "la agenda del jueves", HOY, true))
				.contains(RangoDeDias.delDia(LocalDate.of(2026, 11, 20)));
	}

	@ParameterizedTest
	@CsvSource({
			"'pasame la agenda del 20/11',     2026-11-20",
			"'turnos del 3/9',                 2026-09-03",
			"'cuantos vinieron el 20/11/2025', 2025-11-20",
			"'agenda 20/11/25',                2025-11-20",
			"'el 31/02 no existe, el 1/3 si',  2026-03-01"})
	void una_fecha_escrita_en_la_pregunta_manda_sobre_el_modelo(String pregunta, LocalDate dia) {
		assertThat(ResolutorDePeriodo.resolver("HOY", pregunta, HOY, false)).contains(RangoDeDias.delDia(dia));
	}

	@Test
	void sin_periodo_ni_fecha_escrita_la_intencion_decide() {
		assertThat(ResolutorDePeriodo.resolver(null, "se cargo la sesion de maria?", HOY, false)).isEmpty();
	}

	@Test
	void una_fecha_del_modelo_que_no_existe_este_anio_queda_con_su_anio() {
		// Code review del 15/11: FECHA:2024-02-29 resuelta como 2026-02-29 lanzaba una excepcion.
		assertThat(ResolutorDePeriodo.resolver("FECHA:2024-02-29", "turnos del 29 de febrero", HOY, true))
				.contains(RangoDeDias.delDia(LocalDate.of(2024, 2, 29)));
	}

	@Test
	void hacia_adelante_un_mes_anterior_al_actual_es_del_proximo_anio() {
		assertThat(ResolutorDePeriodo.resolver("MES:ENERO", "", HOY, true))
				.contains(new RangoDeDias(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)));
		assertThat(ResolutorDePeriodo.resolver("MES:DICIEMBRE", "", HOY, true))
				.contains(new RangoDeDias(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)));
	}
}
