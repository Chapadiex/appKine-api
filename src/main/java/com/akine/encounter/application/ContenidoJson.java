package com.akine.encounter.application;

import com.akine.encounter.domain.exception.BorradorNoEsJsonException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lo unico que el servidor exige del borrador: que sea un documento JSON que la columna acepte.
 *
 * <p>El contenido sigue siendo <b>opaco</b> —que campos tiene lo decide la pantalla— pero
 * {@code sesion.borrador} es una columna {@code JSON} de MySQL, y un texto que no lo es moria en
 * la base como violacion de integridad: 409 generico, que manda a recargar y reintentar lo mismo.
 * Validarlo aca lo convierte en el 400 que corresponde, antes de escribir.
 *
 * <p>Se rechaza lo mismo que rechazaria MySQL, no mas: texto que no parsea, texto vacio, basura
 * despues del documento y anidamiento por encima del limite del motor (profundidad 100, donde un
 * escalar cuenta 1: se admiten hasta 99 contenedores anidados). {@code null} es legitimo: vacia
 * el borrador.
 */
final class ContenidoJson {

	/** MySQL rechaza documentos de profundidad mayor a 100; un escalar ya cuenta 1. */
	static final int MAX_CONTENEDORES_ANIDADOS = 99;

	private static final JsonMapper MAPPER = JsonMapper.builder()
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.build();

	private ContenidoJson() {
	}

	static void exigirValido(String contenido) {
		if (contenido == null) {
			return;
		}
		JsonNode documento;
		try {
			documento = MAPPER.readTree(contenido);
		} catch (JacksonException invalido) {
			throw new BorradorNoEsJsonException("El contenido tiene que ser un documento JSON valido");
		}
		if (documento == null || documento.isMissingNode()) {
			throw new BorradorNoEsJsonException(
					"El contenido esta vacio: para vaciar el borrador manda null");
		}
		if (profundidad(documento) > MAX_CONTENEDORES_ANIDADOS) {
			throw new BorradorNoEsJsonException("El contenido anida mas de "
					+ MAX_CONTENEDORES_ANIDADOS + " niveles de objetos o listas");
		}
	}

	/** Contenedores anidados: un escalar es 0, {@code {}} es 1, {@code [[1]]} es 2. */
	private static int profundidad(JsonNode nodo) {
		if (!nodo.isContainer()) {
			return 0;
		}
		int maxima = 0;
		for (JsonNode hijo : nodo.values()) {
			maxima = Math.max(maxima, profundidad(hijo));
		}
		return maxima + 1;
	}
}
