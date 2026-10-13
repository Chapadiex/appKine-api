package com.akine.scheduling.domain.exception;

/**
 * La sede existe en el tenant pero esta dada de baja: no admite turnos nuevos ni que se muevan
 * turnos a ella (RF-M03-004).
 *
 * <p>Se lanza <b>bajo el lock de {@code agenda_sede}</b>, despues de releer la sede con lock
 * compartido (decision pendiente 8b): es lo que cierra la carrera contra la baja de la sede, que
 * toma el exclusivo de la misma fila antes de preguntar si quedan turnos pendientes. Responde el
 * mismo 409 {@code consultorio-inactive} que {@code resource} ya devuelve para los hechos nuevos en
 * una sede de baja.
 */
public class SedeDadaDeBajaException extends RuntimeException {

	private final long consultorioId;

	public SedeDadaDeBajaException(long consultorioId) {
		super("La sede " + consultorioId + " esta dada de baja: no admite turnos nuevos");
		this.consultorioId = consultorioId;
	}

	public long getConsultorioId() {
		return consultorioId;
	}
}
