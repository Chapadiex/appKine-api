package com.akine.knowledge.infrastructure;

import com.akine.knowledge.domain.Vectores;
import com.akine.knowledge.domain.port.ModeloEmbeddings;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Modelo determinista para tests y desarrollo ({@code akine.biblioteca.modelo.tipo=prueba}):
 * bolsa de palabras con hashing a 768 dimensiones, normalizada. Textos con palabras en comun
 * dan coseno positivo. Los prefijos de e5 se quitan antes, asi consulta y pasaje con las
 * mismas palabras quedan cerca. No entiende sinonimos: no sirve para medir calidad.
 */
public class ModeloEmbeddingsDePrueba implements ModeloEmbeddings {

	static final String ID = "prueba-hash-768";
	static final int DIMENSION = 768;

	private static final Pattern MARCAS = Pattern.compile("\\p{M}+");
	private static final Pattern NO_LETRAS = Pattern.compile("[^\\p{L}]+");

	@Override
	public String id() {
		return ID;
	}

	@Override
	public int dimension() {
		return DIMENSION;
	}

	@Override
	public List<float[]> pasajes(List<String> textos) {
		return textos.stream().map(t -> vector("passage: " + t)).toList();
	}

	@Override
	public float[] consulta(String texto) {
		return vector("query: " + texto);
	}

	private static float[] vector(String texto) {
		String sinPrefijo = texto.startsWith("passage: ") ? texto.substring(9)
				: texto.startsWith("query: ") ? texto.substring(7) : texto;
		String limpio = MARCAS.matcher(Normalizer.normalize(sinPrefijo, Normalizer.Form.NFD))
				.replaceAll("").toLowerCase(Locale.ROOT);
		float[] v = new float[DIMENSION];
		for (String palabra : NO_LETRAS.split(limpio)) {
			if (!palabra.isEmpty()) {
				v[Math.floorMod(palabra.hashCode(), DIMENSION)] += 1f;
			}
		}
		return Vectores.normalizar(v);
	}
}
