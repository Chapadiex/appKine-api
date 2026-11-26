package com.akine.organization.domain.exception;

/** El pedido de cambio de plan no existe, o es de otro centro (404). */
public class PedidoDeCambioDePlanNotFoundException extends RuntimeException {

	public PedidoDeCambioDePlanNotFoundException(long id) {
		super("Pedido de cambio de plan inexistente: " + id);
	}
}
