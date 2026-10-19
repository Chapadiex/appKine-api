package com.akine.encounter.application;

/**
 * La misma {@code Idempotency-Key} llego con un pedido distinto (409
 * {@code idempotency-key-conflict}). DP-43, inicio sin turno.
 *
 * <p>Reintentar el mismo pedido devuelve la atencion ya creada; uno distinto con la misma clave no
 * es un reintento, y devolver la atencion vieja le haria creer al cliente que abrio la nueva.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

	public IdempotencyKeyConflictException(String idempotencyKey) {
		super("La clave de idempotencia " + idempotencyKey + " ya se uso con otro pedido");
	}
}
