package com.akine.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CuitTest {

	@ParameterizedTest
	@ValueSource(strings = {"20-12345678-6", "20123456786", "30-71234567-1", "27-00000000-6", " 20-12345678-6 "})
	@DisplayName("un CUIT bien formado con su digito verificador correcto es valido, con o sin guiones")
	void cuit_valido(String cuit) {
		assertThat(Cuit.pareceCuit(cuit)).isTrue();
		assertThat(Cuit.esValido(cuit)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"20-12345678-5", "20123456787", "30-71234567-0"})
	@DisplayName("un tipeo de un digito rompe el verificador y el CUIT es invalido")
	void cuit_con_verificador_incorrecto(String cuit) {
		assertThat(Cuit.pareceCuit(cuit)).isTrue();
		assertThat(Cuit.esValido(cuit)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {"B12345678", "76.123.456-K", "2012345678", "20-1234567-89", "abc", ""})
	@DisplayName("lo que no tiene forma de CUIT no parece un CUIT: se acepta como texto y no se valida")
	void lo_que_no_parece_cuit(String valor) {
		assertThat(Cuit.pareceCuit(valor)).isFalse();
		assertThat(Cuit.esValido(valor)).isFalse();
	}

	@Test
	@DisplayName("null no parece un CUIT y normalizar lo deja en null")
	void nulo() {
		assertThat(Cuit.pareceCuit(null)).isFalse();
		assertThat(Cuit.normalizar(null)).isNull();
		assertThat(Cuit.normalizar("20-12345678-6")).isEqualTo("20123456786");
	}
}
