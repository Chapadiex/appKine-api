package com.akine.identity.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Generador de los valores opacos que SON la credencial de los enlaces y del refresh. */
class RandomTokenGeneratorTest {

	private final RandomTokenGenerator generator = new RandomTokenGenerator();

	@Test
	@DisplayName("mil tokens seguidos no repiten ninguno")
	void mil_tokens_no_repiten() {
		Set<String> vistos = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			assertThat(vistos.add(generator.nuevoToken()))
					.as("token repetido en la iteracion %d", i)
					.isTrue();
		}
	}

	@Test
	@DisplayName("el token trae 32 bytes de entropia y es seguro para URL")
	void el_token_trae_256_bits_y_es_seguro_para_url() {
		String token = generator.nuevoToken();

		// 32 bytes en Base64 sin relleno son 43 caracteres. Menos que eso seria menos entropia
		// de la que el contrato del puerto exige, y es lo que permite guardar solo el SHA-256.
		assertThat(token).hasSize(43);
		assertThat(token).doesNotContain("+", "/", "=");
		assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
	}

	@Test
	@DisplayName("la familia es un UUID: agrupa sesiones, no autentica a nadie")
	void la_familia_es_un_uuid() {
		String familia = generator.nuevaFamilia();

		assertThat(familia).hasSize(36);
		assertThatCode(() -> UUID.fromString(familia)).doesNotThrowAnyException();
		assertThat(familia).isNotEqualTo(generator.nuevaFamilia());
	}
}
