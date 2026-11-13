package com.akine.knowledge.spi;

/**
 * Un fragmento de un libro de la biblioteca devuelto por una busqueda.
 *
 * @param puntaje similitud en {@code [0,1]} (coseno o relevancia normalizada segun el camino)
 * @param superaCorte {@code puntaje >= corte} del camino usado
 */
public record PasajeRecuperado(
		long libroId, String titulo, int paginaDesde, int paginaHasta, String texto,
		double puntaje, boolean superaCorte) {
}
