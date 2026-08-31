package com.akine.scheduling.domain.exception;

/**
 * El profesional o el espacio ya tienen otro turno que se cruza con este intervalo. <b>409</b>.
 *
 * <p><b>Se cruza, no coincide.</b> Dos ofertas con duraciones distintas producen slots que no caen
 * en la misma grilla: un turno de 09:00 a 10:00 y otro de 09:30 a 10:00 se pisan sin compartir
 * ningun valor de columna. Por eso ningun unique de la base puede hacer cumplir esta regla y la
 * decide la consulta de solapamiento bajo el lock de la sede.
 */
public class RecursoOcupadoException extends RuntimeException {

	private final String recurso;

	public RecursoOcupadoException(String recurso) {
		super("El " + recurso + " ya tiene otro turno que se cruza con el intervalo pedido");
		this.recurso = recurso;
	}

	public String getRecurso() {
		return recurso;
	}
}
