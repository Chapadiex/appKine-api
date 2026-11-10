package com.akine.knowledge.spi;

/** Como se resolvio una busqueda en la biblioteca. */
public enum CaminoBusqueda {
	/** Similitud de vectores con el modelo de embeddings vigente. */
	VECTOR,
	/** Texto completo de MySQL: el modelo no esta o fallo. */
	PALABRAS
}
