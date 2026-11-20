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

	@Test
	void losConceptosSeNombranComoEnLaPantalla() {
		assertThat(TokenizadorDelManual.tokens("¿cómo agrego un paciente nuevo?"))
				.containsExactly("alta", "persona", "alta");
		assertThat(TokenizadorDelManual.tokens("¿dónde cargo una prepaga?")).containsExactly("alta", "financiador");
		assertThat(TokenizadorDelManual.tokens("¿cómo cambio un turno de día?"))
				.containsExactly("editar", "turno", "dia");
		assertThat(TokenizadorDelManual.tokens("Reprogramar un turno")).containsExactly("editar", "turno");
		assertThat(TokenizadorDelManual.tokens("¿cómo cierro la atención?")).containsExactly("cerrar", "atencion");
	}
}
