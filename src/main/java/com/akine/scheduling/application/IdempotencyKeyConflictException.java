package com.akine.scheduling.application;

/**
 * La misma clave de idempotencia se reuso con un pedido distinto. <b>409</b>.
 *
 * <p>Vive en {@code application} y no en {@code domain} porque no es una regla del dominio de
 * turnos: es una regla del protocolo entre el cliente y esta API. Un turno no sabe ni le importa
 * que alguien haya reintentado.
 *
 * <p>Cierra, para este endpoint, el agujero que AKINE-01.01 dejo como escenario diferido 7b:
 * {@code onboarding_registro} no guarda el hash del pedido, asi que alli el reuso con otro cuerpo
 * todavia devuelve en silencio el resultado anterior.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

	private final String idempotencyKey;

	public IdempotencyKeyConflictException(String idempotencyKey) {
		super("La clave de idempotencia se reuso con un pedido distinto");
		this.idempotencyKey = idempotencyKey;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}
}
