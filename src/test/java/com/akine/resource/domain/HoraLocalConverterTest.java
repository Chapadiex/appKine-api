package com.akine.resource.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HoraLocalConverter} sin Spring y sin base: prueba directa del mapper, ida y vuelta.
 *
 * <p>Es el converter que el brief senala como la parte de mayor riesgo de la tarea: un bug
 * unidireccional aca acorta en silencio todo bloque que llega a medianoche, y ningun test de
 * integracion sobre las tablas (que no ejercitan JPA) lo hubiera detectado. Cubre exactamente
 * los cuatro casos de {@code '24:00:00'}/{@code LocalTime.MAX}, el borde de las 00:00 —que es la
 * hora que usa un bloque que EMPIEZA a medianoche— y el contrato de {@code null} en las dos
 * direcciones.
 */
class HoraLocalConverterTest {

	private final HoraLocalConverter converter = new HoraLocalConverter();

	@Test
	void fin_de_dia_se_escribe_como_24_00_00() {
		assertThat(converter.convertToDatabaseColumn(LocalTime.MAX)).isEqualTo("24:00:00");
	}

	@Test
	void _24_00_00_se_lee_como_fin_de_dia() {
		assertThat(converter.convertToEntityAttribute("24:00:00")).isEqualTo(LocalTime.MAX);
	}

	@Test
	void una_hora_comun_hace_ida_y_vuelta_sin_cambios() {
		LocalTime dieciocho = LocalTime.of(18, 0);

		String enBase = converter.convertToDatabaseColumn(dieciocho);
		assertThat(enBase).isEqualTo("18:00:00");
		assertThat(converter.convertToEntityAttribute(enBase)).isEqualTo(dieciocho);
	}

	@Test
	void el_inicio_de_dia_hace_ida_y_vuelta_sin_cambios() {
		// El borde opuesto a FIN_DE_DIA: es la hora que usa un bloque que arranca a medianoche,
		// y no tiene que confundirse con el tratamiento especial de las 24:00.
		LocalTime medianoche = LocalTime.of(0, 0);

		String enBase = converter.convertToDatabaseColumn(medianoche);
		assertThat(enBase).isEqualTo("00:00:00");
		assertThat(converter.convertToEntityAttribute(enBase)).isEqualTo(medianoche);
	}

	@Test
	void null_hacia_la_base_es_null() {
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
	}

	@Test
	void null_desde_la_base_es_null() {
		assertThat(converter.convertToEntityAttribute(null)).isNull();
	}
}
