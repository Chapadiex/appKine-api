package com.akine.notification.domain.exception;

import com.akine.notification.domain.OutboxStatus;

/**
 * Transicion de estado no contemplada por {@code OutboxStateMachine}.
 *
 * <p>No es un error del usuario: es un bug del worker o de un reintento mal disparado. Se
 * traduce a 500, nunca a un 4xx, porque el cliente no puede hacer nada con ella.
 */
public class InvalidOutboxTransitionException extends RuntimeException {

	private final OutboxStatus desde;
	private final OutboxStatus hacia;

	public InvalidOutboxTransitionException(OutboxStatus desde, OutboxStatus hacia) {
		super("Transicion de notificacion no permitida: " + desde + " -> " + hacia);
		this.desde = desde;
		this.hacia = hacia;
	}

	public OutboxStatus getDesde() {
		return desde;
	}

	public OutboxStatus getHacia() {
		return hacia;
	}
}
