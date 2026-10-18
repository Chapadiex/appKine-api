package com.akine.activity.domain.exception;

/**
 * El participante es una Persona sin perfil de paciente vigente y el pedido no dijo que se lo
 * activara (409 {@code persona-sin-perfil-paciente}).
 *
 * <p>RF-M07-010: derivar no convierte a nadie en paciente de costado. Lleva el {@code personaId}
 * para que la pantalla ofrezca el paso explicito ({@code activarPerfilPaciente = true}).
 */
public class PersonaSinPerfilPacienteException extends RuntimeException {

	private final long personaId;

	public PersonaSinPerfilPacienteException(long personaId) {
		super("La persona " + personaId + " no tiene perfil de paciente vigente");
		this.personaId = personaId;
	}

	public long getPersonaId() {
		return personaId;
	}
}
