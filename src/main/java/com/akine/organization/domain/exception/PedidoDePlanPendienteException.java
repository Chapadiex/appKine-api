package com.akine.organization.domain.exception;

/** El centro ya tiene un pedido de cambio de plan esperando respuesta (409). */
public class PedidoDePlanPendienteException extends RuntimeException {

	public PedidoDePlanPendienteException() {
		super("Ya hay un pedido de cambio de plan esperando respuesta");
	}
}
