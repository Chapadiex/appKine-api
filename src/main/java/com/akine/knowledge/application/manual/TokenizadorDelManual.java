package com.akine.knowledge.application.manual;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
			"quiero", "cuando", "dar", "doy", "das", "dan", "debo", "necesito", "puede", "pueden");

	/**
	 * Verbos de acción que la gente dice de muchas formas: "dar de alta", "cargar", "crear" o "firmar"
	 * un convenio son lo mismo. Se reducen a una forma canónica tanto en la ficha como en la pregunta, y
	 * así no hace falta repetir los sinónimos en cada `palabras`.
	 */
	private static final Map<String, String> SINONIMOS = Map.ofEntries(
			Map.entry("crear", "alta"), Map.entry("creo", "alta"), Map.entry("nuevo", "alta"), Map.entry("nueva", "alta"),
			Map.entry("cargar", "alta"), Map.entry("cargo", "alta"), Map.entry("agregar", "alta"),
			Map.entry("agrego", "alta"), Map.entry("firmar", "alta"), Map.entry("registrar", "alta"),
			Map.entry("registro", "alta"), Map.entry("sumar", "alta"), Map.entry("invitar", "alta"),
			Map.entry("baja", "baja"), Map.entry("eliminar", "baja"), Map.entry("elimino", "baja"),
			Map.entry("borrar", "baja"), Map.entry("borro", "baja"), Map.entry("quitar", "baja"),
			Map.entry("sacar", "baja"), Map.entry("desactivar", "baja"),
			Map.entry("modificar", "editar"), Map.entry("modifico", "editar"), Map.entry("cambiar", "editar"),
			Map.entry("cambio", "editar"), Map.entry("corregir", "editar"), Map.entry("edito", "editar"));

	private TokenizadorDelManual() {
	}

	static List<String> tokens(String texto) {
		if (texto == null || texto.isBlank()) {
			return List.of();
		}
		String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return Arrays.stream(sinTildes.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
				.filter(t -> t.length() > 1 && !VACIAS.contains(t))
				.map(t -> SINONIMOS.getOrDefault(t, t))
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
