package com.akine.activity.application;

/**
 * La misma clave de idempotencia se reuso con un pedido distinto.
 *
 * <p>Reusa el tipo transversal {@code idempotency-key-conflict}: es la misma situacion de protocolo
 * que en M01 y M12, y publicar un tercer tipo para ella obligaria al cliente a manejar tres codigos
 * para un mismo caso.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

	private final String idempotencyKey;

	public IdempotencyKeyConflictException(String idempotencyKey) {
		super("La clave de idempotencia ya se uso con un pedido distinto");
		this.idempotencyKey = idempotencyKey;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}
}
