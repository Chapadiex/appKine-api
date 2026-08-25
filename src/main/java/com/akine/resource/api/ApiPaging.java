package com.akine.resource.api;

/**
 * Normalizacion de los parametros de paginado de este modulo.
 *
 * <p><b>Es una copia deliberada de {@code organization.api.ApiPaging}</b>, que es
 * package-private y por lo tanto inalcanzable desde aca —y aunque fuera publica, ArchUnit
 * prohibe importar la capa {@code api} de otro modulo—. Promoverla a {@code platform} la
 * convertiria en una utilidad global de HTTP compartida, que no es lo que {@code platform.spi}
 * ofrece. Los valores son los MISMOS a proposito: el cliente aprende un solo paginado para toda
 * la API, no uno por modulo.
 *
 * <p><b>Por que se acota y no se valida.</b> Un {@code size} de 100000 no es un error del
 * cliente que merezca un 400: es un pedido que el servidor no va a satisfacer entero. Acotarlo
 * devuelve datos utiles en vez de un fallo, y protege igual.
 */
final class ApiPaging {

	/** Pagina inicial, base cero, como en todo el resto de la API. */
	static final String PAGINA_POR_DEFECTO = "0";

	/** Suficiente para una pantalla sin obligar a paginar de a poco. */
	static final String TAMANO_POR_DEFECTO = "20";

	/** Tope duro por request. */
	static final int TAMANO_MAXIMO = 100;

	private ApiPaging() {
	}

	/** Pagina pedida, nunca negativa. */
	static int pagina(int page) {
		return Math.max(page, 0);
	}

	/** Tamano pedido, acotado a {@value #TAMANO_MAXIMO} y nunca menor a 1. */
	static int tamano(int size) {
		return Math.min(Math.max(size, 1), TAMANO_MAXIMO);
	}
}
