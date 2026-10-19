package com.akine.activity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El reparto del honorario de una clase (DP-38): partes iguales y el centavo al primero. */
class RepartoDeHonorarioTest {

	@Test
	@DisplayName("1.000,00 entre tres: 333,34 / 333,33 / 333,33 y la suma cierra exacta")
	void el_centavo_sobrante_va_al_primero() {
		BigDecimal total = new BigDecimal("1000.00");
		BigDecimal primero = RepartoDeHonorario.parte(total, 3, 1);
		BigDecimal segundo = RepartoDeHonorario.parte(total, 3, 2);
		BigDecimal tercero = RepartoDeHonorario.parte(total, 3, 3);

		assertThat(primero).isEqualByComparingTo("333.34");
		assertThat(segundo).isEqualByComparingTo("333.33");
		assertThat(tercero).isEqualByComparingTo("333.33");
		assertThat(primero.add(segundo).add(tercero)).isEqualByComparingTo(total);
	}

	@Test
	@DisplayName("Un solo instructor se lleva el total; dos con total par, mitades exactas")
	void sin_resto() {
		assertThat(RepartoDeHonorario.parte(new BigDecimal("10500.00"), 1, 1)).isEqualByComparingTo("10500.00");
		assertThat(RepartoDeHonorario.parte(new BigDecimal("10500.00"), 2, 1)).isEqualByComparingTo("5250.00");
		assertThat(RepartoDeHonorario.parte(new BigDecimal("10500.00"), 2, 2)).isEqualByComparingTo("5250.00");
	}

	@Test
	@DisplayName("Un centavo entre dos: el primero lo cobra y el segundo queda en cero")
	void el_segundo_puede_quedar_en_cero() {
		assertThat(RepartoDeHonorario.parte(new BigDecimal("0.01"), 2, 1)).isEqualByComparingTo("0.01");
		assertThat(RepartoDeHonorario.parte(new BigDecimal("0.01"), 2, 2)).isEqualByComparingTo("0.00");
	}

	@Test
	@DisplayName("El total de la clase es tarifa por presentes, al centavo")
	void total_de_la_clase() {
		assertThat(RepartoDeHonorario.totalDeLaClase(new BigDecimal("1500.00"), 7))
				.isEqualByComparingTo("10500.00");
	}

	@Test
	@DisplayName("Una posicion fuera del reparto es un error de programacion")
	void posicion_invalida() {
		assertThatThrownBy(() -> RepartoDeHonorario.parte(BigDecimal.TEN, 2, 3))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RepartoDeHonorario.parte(BigDecimal.TEN, 0, 1))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
