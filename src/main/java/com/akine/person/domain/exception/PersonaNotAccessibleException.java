package com.akine.person.domain.exception;

/**
 * La {@code Persona} pedida no existe, o es de otra organizacion (404).
 *
 * <p><b>Los dos casos responden lo mismo y tienen que seguir haciendolo</b> (ADR-0018). Un 403
 * para "existe pero no es tuya" confirmaria que ese id corresponde a una persona real de otro
 * centro, y bastaria recorrer ids consecutivos para medir el padron de pacientes de la
 * competencia. Es el dato mas sensible que este sistema guarda despues de la historia clinica.
 *
 * <p>Una persona INACTIVA <b>no</b> lanza esta excepcion: se lee con 200 —RN-M07-004 exige que
 * los historicos sigan resolviendo— y lo que rechaza son las operaciones nuevas, que es un 409.
 */
public class PersonaNotAccessibleException extends RuntimeException {

	private final long personaId;

	public PersonaNotAccessibleException(long personaId) {
		super("No existe una persona accesible con id " + personaId);
		this.personaId = personaId;
	}

	public long getPersonaId() {
		return personaId;
	}
}
