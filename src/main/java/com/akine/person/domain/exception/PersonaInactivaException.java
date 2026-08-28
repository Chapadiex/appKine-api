package com.akine.person.domain.exception;

/**
 * La persona esta dada de baja y la operacion exige una vigente (409).
 *
 * <p>409 y no 404: la persona existe y el actor la puede leer —RN-M07-004 exige que el historico
 * siga resolviendo—, lo que no admite la operacion es su estado. Responder 404 seria borrar
 * historia por la puerta de atras: el cliente que acaba de listarla concluiria que desaparecio.
 *
 * <p>Alcanza a editar la ficha y a activar un perfil de paciente sobre ella. El caso borde
 * "perfil dado de baja" que la etapa lista se resuelve por el otro lado: si la PERSONA esta
 * vigente y su perfil anterior fue dado de baja, activar de nuevo es un alta legitima, no un
 * conflicto — el unique de V27 lleva {@code deleted_key} justamente para permitirlo.
 */
public class PersonaInactivaException extends RuntimeException {

	private final long personaId;
	private final String operacion;

	public PersonaInactivaException(long personaId, String operacion) {
		super("La persona " + personaId + " esta dada de baja y no admite " + operacion);
		this.personaId = personaId;
		this.operacion = operacion;
	}

	public long getPersonaId() {
		return personaId;
	}

	public String getOperacion() {
		return operacion;
	}
}
