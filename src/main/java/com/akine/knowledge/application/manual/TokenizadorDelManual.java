package com.akine.knowledge.application.manual;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tokenizado del indice del manual, en memoria: la busqueda por palabras de la biblioteca corre en
 * PostgreSQL y no hay un tokenizador Java para reusar. Minusculas, sin tildes, sin palabras vacias.
 */
final class TokenizadorDelManual {

	/** Desde este largo dos palabras con la misma raiz coinciden ("arqueo" y "arquear"). */
	private static final int RAIZ = 5;

	private static final Set<String> VACIAS = Set.of("a", "al", "ante", "como", "con", "cual", "de", "del",
			"donde", "el", "en", "es", "esta", "este", "hay", "la", "las", "lo", "los", "me", "mi", "no", "o",
			"para", "por", "puedo", "que", "se", "si", "sin", "su", "un", "una", "y", "hago", "hacer", "tengo",
			"quiero", "cuando");

	private TokenizadorDelManual() {
	}

	static List<String> tokens(String texto) {
		if (texto == null || texto.isBlank()) {
			return List.of();
		}
		String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return Arrays.stream(sinTildes.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
				.filter(t -> t.length() > 1 && !VACIAS.contains(t))
				.toList();
	}

	/** Igual, o con la misma raiz de {@value #RAIZ} letras cuando las dos son largas. */
	static boolean coinciden(String consulta, String documento) {
		if (consulta.equals(documento)) {
			return true;
		}
		return consulta.length() >= RAIZ && documento.length() >= RAIZ
				&& consulta.regionMatches(0, documento, 0, RAIZ);
	}
}
