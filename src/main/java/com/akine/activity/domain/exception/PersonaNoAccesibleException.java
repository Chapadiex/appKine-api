package com.akine.activity.domain.exception;

/**
 * La persona no existe en el padron de esta organizacion. <b>404 y nunca 403</b> (ADR-0018).
 *
 * <p>Distinta de "la ficha esta dada de baja", que es 409: el padron devuelve las fichas de baja
 * justamente para que el consumidor pueda distinguir "no es tuya" de "no esta vigente", y
 * confundirlas convertiria el endpoint en un oraculo de existencia de personas.
 */
public class PersonaNoAccesibleException extends RuntimeException {

	private final long personaId;

	public PersonaNoAccesibleException(long personaId) {
		super("La persona " + personaId + " no existe en este padron");
		this.personaId = personaId;
	}

	public long getPersonaId() {
		return personaId;
	}
}
