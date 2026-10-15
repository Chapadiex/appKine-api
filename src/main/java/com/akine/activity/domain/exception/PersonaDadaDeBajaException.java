package com.akine.activity.domain.exception;

/**
 * 409 {@code conflict}: la ficha de la persona esta dada de baja. No es 404: el padron devuelve
 * las fichas de baja justamente para distinguir "no es tuya" de "no esta vigente".
 */
public class PersonaDadaDeBajaException extends RuntimeException {

	private final long personaId;

	public PersonaDadaDeBajaException(long personaId) {
		super("La ficha de la persona " + personaId + " esta dada de baja");
		this.personaId = personaId;
	}

	public long getPersonaId() {
		return personaId;
	}
}
