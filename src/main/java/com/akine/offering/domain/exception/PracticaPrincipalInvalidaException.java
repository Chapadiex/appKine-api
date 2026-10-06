package com.akine.offering.domain.exception;

/**
 * La practica principal pedida no es coherente con la lista (A-9): falta con una lista no vacia,
 * no esta en la lista, o viene con una lista vacia. Es un error del pedido: <b>400</b>.
 */
public class PracticaPrincipalInvalidaException extends RuntimeException {

	public PracticaPrincipalInvalidaException(String detalle) {
		super(detalle);
	}
}
