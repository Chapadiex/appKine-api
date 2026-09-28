package com.akine.activity.domain.exception;

/**
 * El profesional o el espacio ya estan tomados en ese intervalo, por un turno o por otra clase.
 *
 * <p><b>Reusa el tipo transversal {@code recurso-ocupado}</b>, el mismo que publica M12. Para la
 * pantalla el desenlace es identico —ese recurso esta ocupado en ese horario— y que el evento
 * conflictivo sea un turno o una clase no cambia lo que puede hacer el usuario. Publicar un
 * segundo codigo obligaria al cliente a manejar dos para un mismo caso.
 *
 * <p>Lo que si distingue es {@code recurso} ("profesional" o "espacio"): esos dos si llevan a
 * acciones distintas.
 */
public class RecursoOcupadoException extends RuntimeException {

	private final String recurso;

	public RecursoOcupadoException(String recurso) {
		super("El " + recurso + " ya esta ocupado en ese horario");
		this.recurso = recurso;
	}

	public String getRecurso() {
		return recurso;
	}
}
