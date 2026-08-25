package com.akine.identity.domain.exception;

/**
 * La invitacion no existe, es de otro tenant, o el token presentado no resuelve.
 *
 * <p>Se traduce a {@code 404}, y los tres casos responden igual a proposito (ADR-0018):
 * distinguirlos permitiria enumerar invitaciones ajenas probando ids, y en el camino publico
 * permitiria averiguar si una direccion fue invitada a algun lado.
 */
public class InvitacionNotAccessibleException extends RuntimeException {

	public InvitacionNotAccessibleException() {
		super("La invitacion no es accesible");
	}
}
