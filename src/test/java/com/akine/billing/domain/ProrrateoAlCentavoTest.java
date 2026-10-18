package com.akine.billing.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El reparto de un medio de cobro entre las deudas que paga (DP-39): al centavo y exacto. */
class ProrrateoAlCentavoTest {

	@Test
	@DisplayName("Tres partes iguales de 100.00: 33.34 + 33.33 + 33.33, y el centavo va a la primera")
	void tres_tercios() {
		Map<String, BigDecimal> partes = ProrrateoAlCentavo.repartir(
				new BigDecimal("100.00"), pesos("a", "1", "b", "1", "c", "1"));

		assertThat(partes).containsExactly(
				Map.entry("a", new BigDecimal("33.34")),
				Map.entry("b", new BigDecimal("33.33")),
				Map.entry("c", new BigDecimal("33.33")));
	}

	@Test
	@DisplayName("El centavo que sobra va al mayor resto, no al primero")
	void mayor_resto() {
		// 10.00 en 1/3 y 2/3: 3.333... y 6.666... -> 3.33 y 6.67.
		Map<String, BigDecimal> partes = ProrrateoAlCentavo.repartir(
				new BigDecimal("10.00"), pesos("a", "100.00", "b", "200.00"));

		assertThat(partes).containsExactly(
				Map.entry("a", new BigDecimal("3.33")),
				Map.entry("b", new BigDecimal("6.67")));
	}

	@Test
	@DisplayName("Pesos que suman el importe lo devuelven tal cual, y un peso cero recibe cero")
	void identidad_y_peso_cero() {
		Map<String, BigDecimal> partes = ProrrateoAlCentavo.repartir(
				new BigDecimal("1000.00"), pesos("a", "600.00", "b", "0", "c", "400.00"));

		assertThat(partes.get("a")).isEqualByComparingTo("600.00");
		assertThat(partes.get("b")).isEqualByComparingTo("0");
		assertThat(partes.get("c")).isEqualByComparingTo("400.00");
	}

	@Test
	@DisplayName("La suma de las partes es siempre el importe exacto")
	void suma_exacta() {
		Map<String, BigDecimal> pesos = pesos("a", "0.07", "b", "0.11", "c", "13.00", "d", "7.77");
		for (String importe : new String[]{"0.01", "0.99", "1.00", "33.33", "99999.99"}) {
			BigDecimal suma = ProrrateoAlCentavo.repartir(new BigDecimal(importe), pesos).values().stream()
					.reduce(BigDecimal.ZERO, BigDecimal::add);
			assertThat(suma).isEqualByComparingTo(importe);
		}
	}

	@Test
	@DisplayName("Sin pesos positivos, con un peso negativo o con mas de dos decimales, falla")
	void entradas_invalidas() {
		assertThatThrownBy(() -> ProrrateoAlCentavo.repartir(BigDecimal.TEN, pesos("a", "0")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ProrrateoAlCentavo.repartir(BigDecimal.TEN, pesos("a", "-1", "b", "2")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ProrrateoAlCentavo.repartir(new BigDecimal("1.001"), pesos("a", "1")))
				.isInstanceOf(ArithmeticException.class);
	}

	private static Map<String, BigDecimal> pesos(String... claveYPeso) {
		Map<String, BigDecimal> pesos = new LinkedHashMap<>();
		for (int i = 0; i < claveYPeso.length; i += 2) {
			pesos.put(claveYPeso[i], new BigDecimal(claveYPeso[i + 1]));
		}
		return pesos;
	}
}
