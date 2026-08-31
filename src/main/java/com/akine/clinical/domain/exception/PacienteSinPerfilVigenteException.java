package com.akine.clinical.domain.exception;

/**
 * Se intento abrir una historia clinica sobre una persona que no es paciente.
 *
 * <p>Es el recableado de DP-10 hecho cumplir: 04.01 pasa a depender de 03.01 y la historia cuelga
 * de una Persona con {@code PerfilPaciente} <b>vigente</b>. Sin esa precondicion, cualquier alta
 * del padron —alguien que solo se inscribe a una clase— terminaria con historia clinica, que es
 * exactamente el error estructural 2 del plan entrando por otra puerta.
 *
 * <p>Es un conflicto de estado, no una falta de permiso ni un recurso inexistente: la persona
 * existe y el actor puede verla; lo que no admite la operacion es su estado.
 */
public class PacienteSinPerfilVigenteException extends RuntimeException {

	private final long personaId;

	public PacienteSinPerfilVigenteException(long personaId) {
		super("La persona " + personaId + " no tiene un perfil de paciente vigente: "
				+ "no se le puede abrir una historia clinica");
		this.personaId = personaId;
	}

	public long getPersonaId() {
		return personaId;
	}
}
