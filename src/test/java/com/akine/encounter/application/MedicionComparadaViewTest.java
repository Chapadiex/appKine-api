package com.akine.encounter.application;

import com.akine.encounter.domain.LateralidadMedicion;
import com.akine.resource.spi.MedicionTipo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cuando corresponde restar dos mediciones (AKINE-06.03).
 *
 * <p>Se decide en el servidor y no en la pantalla: repetir la regla en TypeScript daria dos
 * versiones, y la que se equivoque va a ser la que el profesional mira.
 */
@DisplayName("MedicionComparadaView")
class MedicionComparadaViewTest {

	@Test
	@DisplayName("Con los dos valores en la misma unidad, el delta es hoy menos antes")
	void delta_en_la_misma_unidad() {
		MedicionComparadaView fila = MedicionComparadaView.de(
				medicion("grados", new BigDecimal("92.5")), medicion("grados", new BigDecimal("80")));

		assertThat(fila.delta()).isEqualByComparingTo("12.5");
		assertThat(fila.unidadesDifieren()).isFalse();
	}

	@Test
	@DisplayName("Con unidades distintas no hay delta: restar grados de centimetros no mide nada")
	void sin_delta_si_cambio_la_unidad() {
		// La unidad la congela la medicion al registrarse, y la definicion pudo cambiar entre las
		// dos sesiones. El aviso viaja para que la pantalla diga por que no hay delta.
		MedicionComparadaView fila = MedicionComparadaView.de(
				medicion("grados", new BigDecimal("92.5")), medicion("cm", new BigDecimal("12")));

		assertThat(fila.delta()).isNull();
		assertThat(fila.unidadesDifieren()).isTrue();
	}

	@Test
	@DisplayName("Una medida que hoy falta se nombra con la de antes, y no tiene delta")
	void solo_la_anterior() {
		MedicionComparadaView fila =
				MedicionComparadaView.de(null, medicion("grados", new BigDecimal("80")));

		assertThat(fila.codigo()).isEqualTo("ROM_RODILLA_FLEX");
		assertThat(fila.actual()).isNull();
		assertThat(fila.delta()).isNull();
		assertThat(fila.unidadesDifieren())
				.as("sin dos lados no hay unidades que difieran").isFalse();
	}

	@Test
	@DisplayName("Una fila sin ningun lado es un error de armado, no una medida vacia")
	void sin_ningun_lado() {
		assertThatThrownBy(() -> MedicionComparadaView.de(null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static MedicionView medicion(String unidad, BigDecimal valor) {
		return new MedicionView(
				900L, 12L, "ROM_RODILLA_FLEX", "ROM de rodilla en flexion",
				MedicionTipo.NUMERICO, unidad, 2L, LateralidadMedicion.IZQUIERDA,
				valor, null, null, null, Instant.parse("2026-09-20T13:44:10Z"), 1L);
	}
}
