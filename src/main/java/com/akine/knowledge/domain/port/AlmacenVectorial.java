package com.akine.knowledge.domain.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * Busqueda de fragmentos de la biblioteca de UNA organizacion. Solo devuelve fragmentos de libros
 * activos y LISTO que no sean sospechosos; ninguna implementacion puede cruzar organizaciones.
 */
public interface AlmacenVectorial {

	/**
	 * Los {@code k} fragmentos mas parecidos a {@code consulta} (vector normalizado) entre los que
	 * tienen vector de {@code modelo}, por puntaje descendente.
	 */
	List<Coincidencia> porVector(long orgId, String modelo, float[] consulta, int k);

	/**
	 * Los {@code k} fragmentos con mas relevancia de texto completo para {@code consulta}, tengan o
	 * no vector. El puntaje queda en {@code [0,1)}.
	 */
	List<Coincidencia> porPalabras(long orgId, String consulta, int k);

	/**
	 * Texto y libro de los fragmentos pedidos, filtrados otra vez por organizacion y por las mismas
	 * condiciones de la busqueda. Los ids que no cumplen se omiten.
	 */
	List<FragmentoEncontrado> fragmentos(long orgId, List<Long> fragmentoIds);

	/** @param puntaje escala 4 */
	record Coincidencia(long fragmentoId, BigDecimal puntaje) {
	}

	record FragmentoEncontrado(
			long fragmentoId, long libroId, String titulo, int paginaDesde, int paginaHasta,
			String texto) {
	}
}
