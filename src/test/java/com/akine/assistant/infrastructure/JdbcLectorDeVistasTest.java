package com.akine.assistant.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcLectorDeVistasTest {

	@Test
	void el_error_del_motor_sale_sin_los_valores_citados() {
		assertThat(JdbcLectorDeVistas.sinValores("invalid input syntax for type integer: \"Gomez 20345678\""))
				.isEqualTo("invalid input syntax for type integer: \"…\"");
		assertThat(JdbcLectorDeVistas.sinValores("invalid input syntax for type date: 'xx' y 'otro'"))
				.isEqualTo("invalid input syntax for type date: '…' y '…'");
		assertThat(JdbcLectorDeVistas.sinValores("operator does not exist: integer = text")).contains("integer = text");
		assertThat(JdbcLectorDeVistas.sinValores(null)).isEmpty();
	}
}
