package com.akine.organization.domain.exception;

import com.akine.organization.domain.SubscriptionStatus;

/**
 * Se pidio una transicion de suscripcion que la maquina de estados no admite (RF-M01-003).
 *
 * <p>Cubre tres casos distintos con el mismo significado de negocio: salir de un estado
 * terminal, un salto que no esta en la tabla de transiciones, y repetir la transicion ya
 * aplicada ({@code from == to}). El ultimo importa tanto como los otros: un reintento no
 * puede producir un segundo efecto ni una segunda fila de historico.
 */
public class InvalidSubscriptionTransitionException extends RuntimeException {

	private final SubscriptionStatus from;
	private final SubscriptionStatus to;

	public InvalidSubscriptionTransitionException(SubscriptionStatus from, SubscriptionStatus to) {
		super("Transicion de suscripcion no permitida: " + from + " -> " + to);
		this.from = from;
		this.to = to;
	}

	/** Estado actual. {@code null} cuando se intentaba el alta inicial de la suscripcion. */
	public SubscriptionStatus getFrom() {
		return from;
	}

	public SubscriptionStatus getTo() {
		return to;
	}
}
