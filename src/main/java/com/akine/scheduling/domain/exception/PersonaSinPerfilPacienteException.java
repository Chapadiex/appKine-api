package com.akine.scheduling.domain.exception;

/**
 * La persona existe en el padron pero no es paciente. <b>409</b>.
 *
 * <p>RF-M07-010 y la decision estructural de 03.01: una {@code Persona} no es un {@code Paciente}
 * y la unica forma de serlo es tener perfil vigente. Un turno se reserva para un paciente, asi que
 * este camino <b>no</b> crea el perfil en silencio: activarlo es una accion propia, con su actor y
 * su registro.
 *
 * <p>409 y no 404 porque la persona existe y quien pregunta la esta viendo: el 404 mandaria a la
 * pantalla a decir "no encontrada" sobre una ficha que tiene delante.
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
