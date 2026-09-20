package com.akine.activity.domain.exception;

/**
 * Esa persona ya tiene una inscripcion viva en esa clase (validacion obligatoria de RF-M28-002:
 * "no duplicar inscripcion activa de la misma persona en la misma clase").
 *
 * <p>Reusa el tipo de problema {@code conflict} y no publica uno propio: para la pantalla el
 * desenlace es "ya esta anotada", y un codigo nuevo obligaria al cliente a manejar dos para el
 * mismo caso. Lo que si viaja es el id de la inscripcion existente, para que pueda mostrarla.
 */
public class InscripcionDuplicadaException extends RuntimeException {

	private final long claseId;
	private final long personaId;
	private final long inscripcionExistenteId;

	public InscripcionDuplicadaException(
			long claseId, long personaId, long inscripcionExistenteId) {

		super("La persona " + personaId + " ya tiene una inscripcion viva en la clase " + claseId);
		this.claseId = claseId;
		this.personaId = personaId;
		this.inscripcionExistenteId = inscripcionExistenteId;
	}

	public long getClaseId() {
		return claseId;
	}

	public long getPersonaId() {
		return personaId;
	}

	public long getInscripcionExistenteId() {
		return inscripcionExistenteId;
	}
}
