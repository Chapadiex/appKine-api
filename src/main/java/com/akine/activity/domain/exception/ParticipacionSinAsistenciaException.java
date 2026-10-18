package com.akine.activity.domain.exception;

/**
 * La participacion no tiene asistencia, o la tiene con la persona ausente (409
 * {@code participacion-sin-asistencia}). <b>No se deriva a quien no vino.</b>
 *
 * <p>Lleva el {@code resultado} cuando hay asistencia, para que la pantalla ofrezca corregirla en
 * vez de un mensaje ciego: si el mostrador marco ausente por error, el camino es la correccion de
 * 08.03, no forzar la derivacion.
 */
public class ParticipacionSinAsistenciaException extends RuntimeException {

	private final long inscripcionId;
	private final String resultado;

	public ParticipacionSinAsistenciaException(long inscripcionId, String resultado) {
		super(resultado == null
				? "La inscripcion " + inscripcionId + " no tiene asistencia registrada"
				: "La inscripcion " + inscripcionId + " tiene asistencia " + resultado
						+ ": no se deriva a quien no estuvo");
		this.inscripcionId = inscripcionId;
		this.resultado = resultado;
	}

	public long getInscripcionId() {
		return inscripcionId;
	}

	/** {@code null} si no hay asistencia registrada. */
	public String getResultado() {
		return resultado;
	}
}
