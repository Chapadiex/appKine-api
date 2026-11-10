package com.akine.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class TroceadorTest {

	private static String palabras(int caracteres) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		while (sb.length() < caracteres) {
			sb.append("palabra").append(i++).append(' ');
		}
		String corte = sb.substring(0, caracteres); return corte.substring(0, corte.lastIndexOf(" ")).strip();
	}

	@Test
	void textoDe2500CaracteresDa3PedazosConSolapeYSinCortarPalabras() {
		String texto = palabras(2500);
		List<Pedazo> pedazos = new Troceador(1000, 200, 100).trocear(List.of(new PaginaDeTexto(1, texto)));

		assertThat(pedazos).hasSize(3);
		for (Pedazo p : pedazos) {
			assertThat(p.texto().length()).isLessThanOrEqualTo(1000);
			// cada palabra del pedazo existe completa en el original
			for (String palabra : p.texto().split(" ")) {
				assertThat(palabra).matches("palabra\\d+");
			}
		}
		for (int i = 1; i < pedazos.size(); i++) {
			String anterior = pedazos.get(i - 1).texto();
			String cola = anterior.substring(anterior.length() - 150);
			assertThat(pedazos.get(i).texto()).contains(cola.substring(cola.indexOf(' ') + 1));
		}
	}

	@Test
	void pedazoQueCruzaPaginasConservaElRango() {
		String a = palabras(700);
		String b = palabras(700);
		List<Pedazo> pedazos = new Troceador(1000, 200, 100)
				.trocear(List.of(new PaginaDeTexto(1, a), new PaginaDeTexto(2, b)));

		assertThat(pedazos.get(0).paginaDesde()).isEqualTo(1);
		assertThat(pedazos.get(0).paginaHasta()).isEqualTo(2);
		assertThat(pedazos.get(pedazos.size() - 1).paginaHasta()).isEqualTo(2);
	}

	@Test
	void textoCortoDaUnPedazoYVacioNinguno() {
		Troceador t = new Troceador(1000, 200, 100);
		assertThat(t.trocear(List.of(new PaginaDeTexto(1, "hola  mundo")))).hasSize(1);
		assertThat(t.trocear(List.of(new PaginaDeTexto(1, "  \n ")))).isEmpty();
	}

	@Test
	void palabraMasLargaQueElTamanoSeCortaDuro() {
		List<Pedazo> pedazos = new Troceador(50, 10, 5).trocear(List.of(new PaginaDeTexto(1, "x".repeat(130))));
		assertThat(pedazos.size()).isGreaterThan(1);
		assertThat(pedazos).allSatisfy(p -> assertThat(p.texto().length()).isLessThanOrEqualTo(55));
	}

	@Test
	void restoChicoSePegaAlPedazoAnterior() {
		String texto = palabras(1040);
		List<Pedazo> pedazos = new Troceador(1000, 200, 100).trocear(List.of(new PaginaDeTexto(1, texto)));
		assertThat(pedazos).hasSize(1);
		assertThat(pedazos.get(0).texto()).isEqualTo(texto);
	}
}
