package com.akine.identity.domain.exception;

import com.akine.identity.domain.EstadoCuenta;

/**
 * La transicion de estado pedida no esta en la tabla de {@code AccountStateMachine}.
 *
 * <p>Se traduce a {@code 409 conflict}: el actor tiene derecho a la operacion, lo que no se
 * puede es aplicarla desde el estado actual. Bloquear una cuenta ya bloqueada entra aca a
 * proposito, en vez de responder un 200 silencioso: dos administradores operando a la vez
 * tienen que enterarse de que el otro llego primero.
 */
public class InvalidAccountTransitionException extends RuntimeException {

	private final transient EstadoCuenta desde;
	private final transient EstadoCuenta hacia;

	public InvalidAccountTransitionException(EstadoCuenta desde, EstadoCuenta hacia) {
		super("Transicion de cuenta no permitida: " + desde + " -> " + hacia);
		this.desde = desde;
		this.hacia = hacia;
	}

	public EstadoCuenta getDesde() {
		return desde;
	}

	public EstadoCuenta getHacia() {
		return hacia;
	}
}
