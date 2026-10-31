package com.akine.scheduling.domain.exception;

import java.time.Instant;

/**
 * El profesional o el espacio ya tienen otro turno que se cruza con este intervalo. <b>409</b>.
 *
 * <p><b>Se cruza, no coincide.</b> Dos ofertas con duraciones distintas producen slots que no caen
 * en la misma grilla: un turno de 09:00 a 10:00 y otro de 09:30 a 10:00 se pisan sin compartir
 * ningun valor de columna. Por eso ningun unique de la base puede hacer cumplir esta regla y la
 * decide la consulta de solapamiento bajo el lock de la sede.
 *
 * <p>Lleva el turno con el que choca, si lo hay, para poder decir "ya tiene un turno entre las 10:00
 * y las 10:45" (TURNO.md §36) y para que el sobreturno muestre quien ocupa el lugar. Una clase de
 * M28 no lo lleva: {@code turnoEnConflictoId} queda {@code null}.
 */
public class RecursoOcupadoException extends RuntimeException {

	private final String recurso;
	private final Long turnoEnConflictoId;
	private final Instant conflictoInicio;
	private final Instant conflictoFin;

	public RecursoOcupadoException(String recurso) {
		this(recurso, null, null, null);
	}

	public RecursoOcupadoException(
			String recurso, Long turnoEnConflictoId, Instant conflictoInicio, Instant conflictoFin) {
		super("El " + recurso + " ya tiene otro turno que se cruza con el intervalo pedido");
		this.recurso = recurso;
		this.turnoEnConflictoId = turnoEnConflictoId;
		this.conflictoInicio = conflictoInicio;
		this.conflictoFin = conflictoFin;
	}

	public String getRecurso() {
		return recurso;
	}

	public Long getTurnoEnConflictoId() {
		return turnoEnConflictoId;
	}

	public Instant getConflictoInicio() {
		return conflictoInicio;
	}

	public Instant getConflictoFin() {
		return conflictoFin;
	}
}
