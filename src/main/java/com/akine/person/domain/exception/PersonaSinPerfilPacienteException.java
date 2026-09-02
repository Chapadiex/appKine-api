package com.akine.person.domain.exception;

/**
 * La persona existe pero no es paciente: no se le puede cargar una cobertura (409).
 *
 * <p>Es RF-M07-010 sostenido desde M08. Una {@code Persona} NO es un {@code Paciente} —son dos
 * tablas y no existe ninguna columna {@code es_paciente}— y una cobertura es del paciente: cargar
 * la obra social de un contacto administrativo o de un familiar responsable no significa nada.
 *
 * <p>409 y no 400: el dato que mando el cliente es correcto y la persona existe; lo que no admite
 * la operacion es el estado de esa ficha. La salida es activar el perfil de paciente, que es una
 * operacion distinta y con su propio permiso.
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
