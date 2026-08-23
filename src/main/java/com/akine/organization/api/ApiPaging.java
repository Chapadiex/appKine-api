package com.akine.organization.api;

/**
 * Normalizacion de los parametros de paginado de la API.
 *
 * <p><b>Por que se acota y no se valida.</b> Un {@code size} de 100000 no es un error del
 * cliente que merezca un 400: es un pedido que el servidor no va a satisfacer entero. Acotarlo
 * devuelve datos utiles en vez de un fallo, y protege igual —que es el punto: sin tope, un solo
 * request puede pedir el historico completo de un tenant y llevarse la memoria del proceso.
 *
 * <p>El tope se declara en la documentacion de cada endpoint para que el cliente sepa que
 * pedir 500 le va a devolver {@value #TAMANO_MAXIMO}.
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
