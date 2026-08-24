package com.akine.organization.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La zona de una sede tiene que ser un identificador IANA y nada mas.
 *
 * <p>El caso que este test existe para impedir es el de los offsets fijos. {@code ZoneId.of}
 * acepta {@code "-03:00"} sin chistar y devuelve un {@code ZoneOffset}, asi que validar con esa
 * llamada sola dejaria entrar una zona que <b>no conoce el horario de verano</b>: el dia que un
 * pais lo reinstaure, toda la agenda de esa sede se corre una hora sin que nadie lo note, y lo
 * que se corre son turnos, sesiones y cortes de caja ya registrados.
 */
class ZonasHorariasTest {

	@Test
	@DisplayName("Una zona IANA se acepta y se devuelve sin espacios")
	void acepta_una_zona_iana() {
		assertThat(ZonasHorarias.exigirValida("  America/Argentina/Cordoba  "))
				.isEqualTo("America/Argentina/Cordoba");
	}

	@ParameterizedTest(name = "rechaza \"{0}\"")
	@ValueSource(strings = {"-03:00", "UTC-3", "Z", "ART", "EST", "GMT+3", "  ", "Marte/Olympus"})
	@DisplayName("Se rechaza todo lo que no sea un identificador de la base IANA")
	void rechaza_lo_que_no_es_iana(String candidata) {
		assertThatThrownBy(() -> ZonasHorarias.exigirValida(candidata))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("La zona es obligatoria: sin ella no hay dia operativo que calcular")
	void la_zona_es_obligatoria() {
		assertThatThrownBy(() -> ZonasHorarias.exigirValida(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("obligatoria");
	}
}
