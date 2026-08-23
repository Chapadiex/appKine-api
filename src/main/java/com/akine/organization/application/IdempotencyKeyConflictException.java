package com.akine.organization.application;

/**
 * La clave de idempotencia ya se uso con un payload distinto.
 *
 * <p>No es lo mismo que un reintento: un reintento legitimo repite la misma clave con el mismo
 * contenido y recibe el resultado original. Repetir la clave cambiando el contenido es un
 * error del cliente, y devolverle el resultado viejo seria peor que fallar —creeria que se
 * creo lo que pidio ahora—. Se responde {@code 409 idempotency-key-conflict}.
 *
 * <p>Vive en {@code application} y no en {@code domain} porque no es una regla del modelo:
 * es una regla del protocolo de reintento, que solo existe en la capa que orquesta.
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
