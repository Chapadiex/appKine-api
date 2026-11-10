package com.akine.knowledge.domain.port;

import com.akine.knowledge.domain.LibroFragmento;

import java.util.List;

public interface LibroFragmentoRepositoryPort {

	/** Borra los fragmentos del libro e inserta los nuevos. El llamador abre la transaccion. */
	void reemplazarDeLibro(long libroId, List<LibroFragmento> fragmentos);

	/**
	 * Ids de fragmentos no sospechosos de libros activos y LISTO, sin vector o con vector de otro
	 * modelo (invariante: vector NULL equivale a modelo NULL), bloqueados con SKIP LOCKED.
	 * Exige transaccion abierta.
	 */
	List<Long> reclamarSinVector(String modeloVigente, int lote);

	void actualizarVector(long id, byte[] vector, String modelo);

	long contarPorLibro(long libroId);

	/** Fragmentos marcados sospechosos, por orden. */
	List<LibroFragmento> sospechososDe(long libroId);
}
