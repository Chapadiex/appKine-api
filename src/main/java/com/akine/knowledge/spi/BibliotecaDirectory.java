package com.akine.knowledge.spi;

/**
 * Busqueda en la biblioteca de una organizacion para otros modulos.
 *
 * <p><b>No exige permiso:</b> lo exige quien llama, que ademas tiene que haber resuelto que
 * {@code orgId} es la organizacion del contexto. Nunca devuelve pasajes de otra organizacion. Con
 * la biblioteca apagada devuelve un resultado vacio que no alcanza.
 */
public interface BibliotecaDirectory {

	/**
	 * @param consulta no en blanco, hasta 600 caracteres
	 * @param k entre 1 y 20
	 * @throws IllegalArgumentException si la consulta o {@code k} no son validos
	 */
	ResultadoBusqueda buscar(long orgId, String consulta, int k);
}
