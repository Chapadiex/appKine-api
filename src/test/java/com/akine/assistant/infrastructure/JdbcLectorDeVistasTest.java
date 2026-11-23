package com.akine.assistant.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcLectorDeVistasTest {

	@Test
	void el_error_del_motor_sale_sin_los_valores_citados() {
		assertThat(JdbcLectorDeVistas.sinValores("invalid input syntax for type integer: \"Gomez 20345678\"", "SELECT 1"))
				.isEqualTo("invalid input syntax for type integer: \"…\"");
		assertThat(JdbcLectorDeVistas.sinValores("invalid input syntax for type date: 'xx' y 'otro'", "SELECT 1"))
				.isEqualTo("invalid input syntax for type date: '…' y '…'");
		assertThat(JdbcLectorDeVistas.sinValores("operator does not exist: integer = text", "SELECT 1")).contains("integer = text");
		assertThat(JdbcLectorDeVistas.sinValores(null, null)).isEmpty();
	}

	@Test
	void conserva_lo_citado_que_el_modelo_escribio_y_borra_lo_demas() {
		String mensaje = "column \"Apellido_x\" does not exist; invalid input for integer: \"Gomez 2034\"";

		assertThat(JdbcLectorDeVistas.sinValores(mensaje, "SELECT apellido_x FROM personas"))
				.isEqualTo("column \"Apellido_x\" does not exist; invalid input for integer: \"…\"");
	}
}
