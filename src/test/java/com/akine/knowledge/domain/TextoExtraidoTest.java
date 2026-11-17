package com.akine.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class TextoExtraidoTest {

	@Test
	void casiSinTextoDetectaEscaneados() {
		assertThat(TextoExtraido.casiSinTexto(List.of())).isTrue();
		assertThat(TextoExtraido.casiSinTexto(List.of(new PaginaDeTexto(1, " \n"), new PaginaDeTexto(2, "ab")))).isTrue();
		assertThat(TextoExtraido.casiSinTexto(List.of(new PaginaDeTexto(1, "x".repeat(60))))).isFalse();
	}
}
