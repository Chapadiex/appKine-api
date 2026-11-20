package com.akine.knowledge.application.manual;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenizadorDelManualTest {

	@Test
	void lasFormasDeUnaAccionSeReducenALaMisma() {
		assertThat(TokenizadorDelManual.tokens("¿Cómo doy de alta un convenio?")).containsExactly("alta", "convenio");
		assertThat(TokenizadorDelManual.tokens("Firmar un convenio")).containsExactly("alta", "convenio");
		assertThat(TokenizadorDelManual.tokens("Eliminar un horario")).containsExactly("baja", "horario");
	}
}
