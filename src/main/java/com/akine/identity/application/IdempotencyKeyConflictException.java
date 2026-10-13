package com.akine.identity.application;

/**
 * El alta self-service se reintento con la misma {@code Idempotency-Key} y otro contenido.
 *
 * <p>Reusa el tipo transversal {@code idempotency-key-conflict} (409), igual que
 * {@code organization}, {@code scheduling}, {@code billing} y {@code activity}. Vive en
 * {@code identity} y no se importa la de {@code organization} porque cada modulo mapea sus
 * propias excepciones en su propio advice (regla 14 del {@code CLAUDE.md} del workspace).
 *
 * <p><b>No es un oraculo de cuentas.</b> El conflicto se decide por la clave y el hash del
 * pedido, que son del cliente; si el email tenia cuenta o no, no cambia el desenlace: los dos
 * caminos del alta guardan el hash.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

	private final transient String idempotencyKey;

	public IdempotencyKeyConflictException(String idempotencyKey) {
		super("La clave de idempotencia ya fue usada con un contenido distinto");
		this.idempotencyKey = idempotencyKey;
	}

	/** Sirve para el log correlacionado. Jamas para el cuerpo de la respuesta. */
	public String getIdempotencyKey() {
		return idempotencyKey;
	}
}
