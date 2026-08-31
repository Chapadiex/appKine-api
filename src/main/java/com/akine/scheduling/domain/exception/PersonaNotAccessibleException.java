package com.akine.scheduling.domain.exception;

/** La persona no existe o es de otra organizacion. <b>404 siempre</b>: ADR-0018. */
public class PersonaNotAccessibleException extends RuntimeException {

	private final long personaId;

	public PersonaNotAccessibleException(long personaId) {
		super("Persona no accesible: " + personaId);
		this.personaId = personaId;
	}

	public long getPersonaId() {
		return personaId;
	}
}
