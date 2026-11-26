package com.akine.organization.domain.exception;

/** Lo pedido es igual a lo que el centro ya tiene contratado (400). */
public class PedidoSinCambiosException extends RuntimeException {

	public PedidoSinCambiosException() {
		super("Lo que pediste es lo que ya tenés contratado");
	}
}
