package com.akine.knowledge.domain.port;

import java.util.List;

/**
 * Modelo que convierte texto en vectores (multilingual-e5: el material va con el prefijo
 * {@code "passage: "} y la consulta con {@code "query: "}). Los vectores salen normalizados.
 */
public interface ModeloEmbeddings {

	/** Identificador que se guarda en {@code libro_fragmento.modelo_embeddings}. */
	String id();

	int dimension();

	/** Un vector por texto, en el mismo orden. Antepone {@code "passage: "}. */
	List<float[]> pasajes(List<String> textos);

	/** Antepone {@code "query: "}. */
	float[] consulta(String texto);
}
