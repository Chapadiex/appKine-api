package com.akine.knowledge.domain;

/**
 * Lo que la capa de aplicacion necesita saber de la configuracion de la biblioteca.
 *
 * @param habilitada interruptor {@code akine.biblioteca.enabled}
 * @param maxBytes tamano maximo de un PDF
 * @param maxPaginas paginas maximas de un PDF al extraer
 * @param consultaHabilitada interruptor {@code akine.biblioteca.consulta.enabled}
 */
public record BibliotecaAjustes(boolean habilitada, long maxBytes, int maxPaginas, boolean consultaHabilitada) {

	public static final int MAX_PAGINAS_POR_DEFECTO = 2000;

	public BibliotecaAjustes(boolean habilitada, long maxBytes, int maxPaginas) {
		this(habilitada, maxBytes, maxPaginas, false);
	}

	public BibliotecaAjustes(boolean habilitada, long maxBytes) {
		this(habilitada, maxBytes, MAX_PAGINAS_POR_DEFECTO, false);
	}
}
