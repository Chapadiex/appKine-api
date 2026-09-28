package com.akine.activity.domain.exception;

/**
 * La inscripcion no admite esa operacion en su estado actual (RN-M28-004).
 *
 * <p><b>No reusa {@code clase-transicion-no-permitida}</b> aunque sean gemelos: un cliente que
 * mapea el tipo a una pantalla terminaria refrescando la clase entera cuando lo que quedo viejo es
 * una fila de la lista de participantes. Es el mismo criterio con el que 08.01 se nego a reusar el
 * tipo de los turnos.
 */
public class TransicionDeInscripcionNoPermitidaException extends RuntimeException {

	private final Long inscripcionId;
	private final String motivo;

	public TransicionDeInscripcionNoPermitidaException(Long inscripcionId, String motivo) {
		super("La inscripcion " + inscripcionId + " no admite esa operacion: " + motivo);
		this.inscripcionId = inscripcionId;
		this.motivo = motivo;
	}

	public Long getInscripcionId() {
		return inscripcionId;
	}

	public String getMotivo() {
		return motivo;
	}
}
