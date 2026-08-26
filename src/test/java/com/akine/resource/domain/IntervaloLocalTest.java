package com.akine.resource.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Aritmetica pura de {@link IntervaloLocal}: la base de todo el calculo de disponibilidad
 * efectiva de la etapa. Sin Spring, sin JPA, sin fechas: solo horas locales.
 *
 * <p>El caso que importa y el que se olvida es {@code restar} devolviendo DOS intervalos: un
 * cierre en el medio de un bloque —almuerzo, reunion— parte la franja en dos y ambas mitades
 * siguen siendo atencion. Un test que solo cubra 0 y 1 resultado deja ese camino sin probar.
 */
class IntervaloLocalTest {

	private static final LocalTime NUEVE = LocalTime.of(9, 0);
	private static final LocalTime DOCE = LocalTime.of(12, 0);
	private static final LocalTime TRECE = LocalTime.of(13, 0);
	private static final LocalTime CATORCE = LocalTime.of(14, 0);
	private static final LocalTime QUINCE = LocalTime.of(15, 0);
	private static final LocalTime DIECIOCHO = LocalTime.of(18, 0);

	@Test
	void rechaza_un_intervalo_que_termina_antes_de_empezar() {
		assertThatThrownBy(() -> new IntervaloLocal(DOCE, NUEVE))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rechaza_un_intervalo_de_duracion_cero() {
		assertThatThrownBy(() -> new IntervaloLocal(NUEVE, NUEVE))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void dos_intervalos_que_se_tocan_en_un_extremo_no_solapan() {
		// 09:00-12:00 y 12:00-15:00 son contiguos, no solapados. El extremo superior es
		// exclusivo: si esto diera true, dos bloques legitimos de manana y tarde serian
		// rechazados como conflicto.
		IntervaloLocal manana = new IntervaloLocal(NUEVE, DOCE);
		IntervaloLocal tarde = new IntervaloLocal(DOCE, QUINCE);

		assertThat(manana.solapaCon(tarde)).isFalse();
		assertThat(tarde.solapaCon(manana)).isFalse();
		assertThat(manana.esContiguoCon(tarde)).isTrue();
	}

	@Test
	void restar_un_cierre_del_medio_devuelve_dos_intervalos() {
		// 09:00-18:00 menos 13:00-14:00 -> [09:00-13:00, 14:00-18:00]
		IntervaloLocal jornada = new IntervaloLocal(NUEVE, DIECIOCHO);
		IntervaloLocal almuerzo = new IntervaloLocal(TRECE, CATORCE);

		List<IntervaloLocal> resto = jornada.restar(almuerzo);

		assertThat(resto).containsExactly(
				new IntervaloLocal(NUEVE, TRECE),
				new IntervaloLocal(CATORCE, DIECIOCHO));
	}

	@Test
	void restar_un_cierre_que_tapa_todo_devuelve_vacio() {
		IntervaloLocal jornada = new IntervaloLocal(NUEVE, DOCE);
		IntervaloLocal cierreTotal = new IntervaloLocal(NUEVE, DOCE);

		assertThat(jornada.restar(cierreTotal)).isEmpty();
	}

	@Test
	void restar_un_cierre_que_no_solapa_devuelve_el_original() {
		IntervaloLocal manana = new IntervaloLocal(NUEVE, DOCE);
		IntervaloLocal cierreDeOtraFranja = new IntervaloLocal(CATORCE, QUINCE);

		assertThat(manana.restar(cierreDeOtraFranja)).containsExactly(manana);
	}

	@Test
	void restar_un_cierre_que_empieza_antes_recorta_solo_el_final() {
		// El cierre arranca antes de la jornada y termina en el medio: solo queda la cola.
		IntervaloLocal jornada = new IntervaloLocal(NUEVE, DIECIOCHO);
		IntervaloLocal cierre = new IntervaloLocal(LocalTime.of(6, 0), DOCE);

		List<IntervaloLocal> resto = jornada.restar(cierre);

		assertThat(resto).containsExactly(new IntervaloLocal(DOCE, DIECIOCHO));
	}

	@Test
	void un_intervalo_hasta_fin_de_dia_es_valido() {
		IntervaloLocal hastaMedianoche = new IntervaloLocal(LocalTime.of(22, 0), IntervaloLocal.FIN_DE_DIA);

		assertThat(hastaMedianoche.hasta()).isEqualTo(LocalTime.MAX);
		assertThat(hastaMedianoche.contiene(new IntervaloLocal(LocalTime.of(23, 0), LocalTime.MAX))).isTrue();
	}
}
