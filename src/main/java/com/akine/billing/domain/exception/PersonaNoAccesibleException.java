package com.akine.billing.domain.exception;

/**
 * La persona de un anticipo no existe en el padron del tenant. <b>404</b>.
 *
 * <p>Un cobro que imputa deudas valida a la persona a traves de ellas. Un anticipo puro no tiene
 * deudas, y sin este control un id de otro centro del SaaS dejaria plata a su nombre. Inexistente y
 * ajena colapsan en el mismo 404: un 403 confirmaria que el id existe.
 */
public class PersonaNoAccesibleException extends RuntimeException {

	private final long personaId;

	public PersonaNoAccesibleException(long personaId) {
		super("La persona " + personaId + " no existe en esta organizacion");
		this.personaId = personaId;
	}

	public long getPersonaId() {
		return personaId;
	}
}
