package com.akine.knowledge.infrastructure.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeConfigTest {

	private static BibliotecaProperties con(boolean enabled, String tipo) {
		return new BibliotecaProperties(enabled, 104857600L, 2000,
				new BibliotecaProperties.Troceo(1000, 200, 100),
				new BibliotecaProperties.Busqueda(0.818, 0.5, 2),
				new BibliotecaProperties.Modelo(tipo, "multilingual-e5-base", "", "", 0),
				new BibliotecaProperties.Worker(true, 5000, 30000, 30000, 32, 15),
				new BibliotecaProperties.Consulta(false, "ollama", "http://localhost:11434", "qwen2.5:1.5b", 90, "30m", 3,
						new BibliotecaProperties.Consulta.Busqueda(20, 20, 60, 5, 6, 0.005, 2, 0.818)),
				2);
	}

	@Test
	void un_tipo_de_modelo_desconocido_con_la_biblioteca_habilitada_no_arranca() {
		assertThatThrownBy(() -> KnowledgeConfig.exigirTipoDeModelo(con(true, "openai")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("akine.biblioteca.modelo.tipo='openai'");
		assertThatCode(() -> KnowledgeConfig.exigirTipoDeModelo(con(true, "prueba"))).doesNotThrowAnyException();
		assertThatCode(() -> KnowledgeConfig.exigirTipoDeModelo(con(false, "openai"))).doesNotThrowAnyException();
	}
}
