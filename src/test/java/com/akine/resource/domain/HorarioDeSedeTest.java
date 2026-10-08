package com.akine.resource.domain;

import com.akine.resource.domain.FranjaHorarioGeneral.Franja;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La quinta etapa del calculo: el horario general de la sede como techo (A-8b, DP-19). Se prueba
 * a traves del calculador, que es por donde la agenda, la reserva y el simulador de impacto lo
 * ven, y no solo sobre {@link HorarioDeSede} suelto.
 */
class HorarioDeSedeTest {

	private static final long ORG = 1L;
	private static final long SEDE = 10L;
	private static final long PROFESIONAL = 100L;

	/** 2026-03-02 es lunes. */
	private static final LocalDate LUNES = LocalDate.of(2026, 3, 2);
	private static final LocalDate MARTES = LUNES.plusDays(1);
	private static final int DIA_LUNES = 1;
	private static final int DIA_MARTES = 2;

	private final DisponibilidadEfectivaCalculator calculador = new DisponibilidadEfectivaCalculator();

	@Test
	void sin_horario_de_sede_cargado_no_se_limita_nada() {
		BloqueDisponibilidad lunes = bloque(1L, DIA_LUNES, 6, 22);

		Map<LocalDate, DiaCalculado> sinLimite = calcular(List.of(lunes), List.of(), HorarioDeSede.de(List.of()));
		Map<LocalDate, DiaCalculado> seisParametros = calculador.calcular(
				PROFESIONAL, LUNES, LUNES.plusDays(7), List.of(lunes), List.of(), Set.of());

		assertThat(sinLimite).isEqualTo(seisParametros);
		assertThat(sinLimite.get(LUNES).franjas()).singleElement().satisfies(f -> {
			assertThat(f.intervalo()).isEqualTo(intervalo(6, 22));
			assertThat(f.recortadoPor()).isNull();
		});
		assertThat(HorarioDeSede.de(null).limita()).isFalse();
	}

	@Test
	void una_franja_que_excede_el_horario_de_la_sede_se_recorta_a_la_parte_comun() {
		BloqueDisponibilidad lunes = bloque(1L, DIA_LUNES, 7, 20);

		DiaCalculado dia = calcular(List.of(lunes), List.of(),
				HorarioDeSede.de(List.of(franja(DIA_LUNES, 9, 13), franja(DIA_LUNES, 15, 18))))
				.get(LUNES);

		assertThat(dia.franjas()).extracting(FranjaEfectiva::intervalo)
				.containsExactly(intervalo(9, 13), intervalo(15, 18));
		assertThat(dia.franjas()).allSatisfy(f -> {
			assertThat(f.origen()).isEqualTo(OrigenFranja.BLOQUE);
			assertThat(f.recortadoPor()).isEqualTo(OrigenFranja.HORARIO_SEDE);
			assertThat(f.reglaId()).as("la franja sigue apuntando al bloque que la produjo").isEqualTo(1L);
		});
	}

	@Test
	void una_franja_que_cae_entera_dentro_del_horario_queda_intacta() {
		BloqueDisponibilidad lunes = bloque(1L, DIA_LUNES, 10, 12);

		DiaCalculado dia = calcular(List.of(lunes), List.of(),
				HorarioDeSede.de(List.of(franja(DIA_LUNES, 8, 20)))).get(LUNES);

		assertThat(dia.franjas()).singleElement().satisfies(f -> {
			assertThat(f.intervalo()).isEqualTo(intervalo(10, 12));
			assertThat(f.recortadoPor()).isNull();
		});
	}

	@Test
	void las_franjas_contiguas_de_la_sede_se_unen_y_no_parten_el_bloque() {
		BloqueDisponibilidad lunes = bloque(1L, DIA_LUNES, 8, 18);

		DiaCalculado dia = calcular(List.of(lunes), List.of(),
				HorarioDeSede.de(List.of(franja(DIA_LUNES, 13, 17), franja(DIA_LUNES, 9, 13))))
				.get(LUNES);

		assertThat(dia.franjas()).extracting(FranjaEfectiva::intervalo)
				.as("09-13 y 13-17 son una sola apertura: partirla haria desaparecer el slot de 12:30")
				.containsExactly(intervalo(9, 17));
	}

	@Test
	void un_dia_con_horario_del_profesional_y_sin_franja_de_la_sede_declara_horario_sede() {
		BloqueDisponibilidad martes = bloque(1L, DIA_MARTES, 9, 13);
		BloqueDisponibilidad lunes = bloque(2L, DIA_LUNES, 9, 13);

		Map<LocalDate, DiaCalculado> efectiva = calcular(List.of(martes, lunes), List.of(),
				HorarioDeSede.de(List.of(franja(DIA_LUNES, 14, 20))));

		assertThat(efectiva.get(MARTES).estaVacio()).isTrue();
		assertThat(efectiva.get(MARTES).razonVacio())
				.as("la sede no abre los martes")
				.isEqualTo(OrigenFranja.HORARIO_SEDE);
		assertThat(efectiva.get(LUNES).razonVacio())
				.as("la sede abre el lunes, pero no en las horas del profesional")
				.isEqualTo(OrigenFranja.HORARIO_SEDE);
		assertThat(efectiva.get(LUNES.plusDays(2)).razonVacio())
				.as("un dia sin horario del profesional sigue siendo 'sin reglas', no HORARIO_SEDE")
				.isNull();
	}

	@Test
	void un_dia_ya_cerrado_conserva_su_razon_aunque_la_sede_tampoco_abra() {
		BloqueDisponibilidad lunes = bloque(1L, DIA_LUNES, 9, 13);

		DiaCalculado dia = calculador.calcular(
				PROFESIONAL, LUNES, LUNES.plusDays(1), List.of(lunes), List.of(), Set.of(LUNES),
				HorarioDeSede.de(List.of(franja(DIA_MARTES, 9, 13)))).get(LUNES);

		assertThat(dia.razonVacio()).isEqualTo(OrigenFranja.FERIADO);
	}

	@Test
	void cubre_exige_el_intervalo_entero_dentro_de_una_franja_de_la_sede() {
		HorarioDeSede horario = HorarioDeSede.de(List.of(franja(DIA_LUNES, 9, 13)));

		assertThat(horario.cubre(LUNES, intervalo(9, 10))).isTrue();
		assertThat(horario.cubre(LUNES, intervalo(12, 14))).isFalse();
		assertThat(horario.cubre(MARTES, intervalo(9, 10))).isFalse();
		assertThat(HorarioDeSede.sinLimite().cubre(MARTES, intervalo(2, 3))).isTrue();
	}

	// =================================================================================

	private Map<LocalDate, DiaCalculado> calcular(
			List<BloqueDisponibilidad> bloques,
			List<DisponibilidadExcepcion> excepciones,
			HorarioDeSede horario) {
		return calculador.calcular(
				PROFESIONAL, LUNES, LUNES.plusDays(7), bloques, excepciones, Set.of(), horario);
	}

	private static Franja franja(int dia, int desde, int hasta) {
		return new Franja(dia, LocalTime.of(desde, 0), LocalTime.of(hasta, 0));
	}

	private static IntervaloLocal intervalo(int desde, int hasta) {
		return new IntervaloLocal(LocalTime.of(desde, 0), LocalTime.of(hasta, 0));
	}

	private static BloqueDisponibilidad bloque(long id, int diaSemana, int desde, int hasta) {
		BloqueDisponibilidad bloque = new BloqueDisponibilidad(
				ORG, SEDE, PROFESIONAL, diaSemana, LocalTime.of(desde, 0), LocalTime.of(hasta, 0),
				LUNES.minusMonths(1), null);
		ReflectionTestUtils.setField(bloque, "id", id);
		return bloque;
	}
}
