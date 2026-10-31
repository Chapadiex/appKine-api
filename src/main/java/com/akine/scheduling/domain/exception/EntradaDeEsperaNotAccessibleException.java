package com.akine.scheduling.domain.exception;

/** La entrada no existe en esa sede. 404 y nunca 403: un 403 confirmaria que existe. */
public class EntradaDeEsperaNotAccessibleException extends RuntimeException {

	public EntradaDeEsperaNotAccessibleException(long entradaId) {
		super("La entrada " + entradaId + " de la lista de espera no existe en esta sede");
	}
}
