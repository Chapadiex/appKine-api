package com.akine.assistant.infrastructure.telegram;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CifradorDeTokensTest {

	private static final String TOKEN = "1234567890:AAHfakeTokenSoloParaTests_0123456789ab";

	@Test
	void cifra_y_descifra_sin_dejar_el_token_a_la_vista() {
		CifradorDeTokens cifrador = new CifradorDeTokens("una clave larga de despliegue");

		String guardado = cifrador.cifrar(TOKEN);

		assertThat(guardado).doesNotContain("AAH").startsWith("v1:");
		assertThat(cifrador.cifrar(TOKEN)).isNotEqualTo(guardado);
		assertThat(cifrador.descifrar(guardado)).isEqualTo(TOKEN);
	}

	@Test
	void con_otra_clave_no_se_descifra() {
		String guardado = new CifradorDeTokens("clave A").cifrar(TOKEN);

		assertThatThrownBy(() -> new CifradorDeTokens("clave B").descifrar(guardado))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
