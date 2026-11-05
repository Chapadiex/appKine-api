package com.akine.scheduling.domain.exception;

import java.time.Instant;

/**
 * El profesional o el espacio ya tienen otro turno, o una clase, que se cruza con este intervalo.
 * <b>409</b>.
 *
 * <p><b>Se cruza, no coincide.</b> Dos ofertas con duraciones distintas producen slots que no caen
 * en la misma grilla: un turno de 09:00 a 10:00 y otro de 09:30 a 10:00 se pisan sin compartir
 * ningun valor de columna. Por eso ningun unique de la base puede hacer cumplir esta regla y la
 * decide la consulta de solapamiento bajo el lock de la sede y de la persona.
 *
 * <p>Lleva el turno con el que choca, si lo hay, para poder decir "ya tiene un turno entre las 10:00
 * y las 10:45" (TURNO.md §36) y para que el sobreturno muestre quien ocupa el lugar. Si choca con una
 * clase de M28, {@code turnoEnConflictoId} queda {@code null} y viajan la clase, su sede y su horario
 * (agenda de clases y avisos, contrato 0.156.0): el {@code type} sigue siendo {@code recurso-ocupado}.
 */
public class RecursoOcupadoException extends RuntimeException {

	/** Discriminadores de {@link #getConflictoTipo()}. */
	public static final String TURNO = "TURNO";
	public static final String CLASE = "CLASE";

	private final String recurso;
	private final Long turnoEnConflictoId;
	private final Instant conflictoInicio;
	private final Instant conflictoFin;
	private final String conflictoTipo;
	private final Long claseEnConflictoId;
	private final Long conflictoConsultorioId;

	public RecursoOcupadoException(String recurso) {
		this(recurso, null, null, null);
	}

	public RecursoOcupadoException(
			String recurso, Long turnoEnConflictoId, Instant conflictoInicio, Instant conflictoFin) {
		this("El " + recurso + " ya tiene otro turno que se cruza con el intervalo pedido",
				recurso, turnoEnConflictoId, conflictoInicio, conflictoFin,
				turnoEnConflictoId == null ? null : TURNO, null, null);
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	private RecursoOcupadoException(
			String mensaje, String recurso, Long turnoEnConflictoId, Instant conflictoInicio,
			Instant conflictoFin, String conflictoTipo, Long claseEnConflictoId, Long conflictoConsultorioId) {
		super(mensaje);
		this.recurso = recurso;
		this.turnoEnConflictoId = turnoEnConflictoId;
		this.conflictoInicio = conflictoInicio;
		this.conflictoFin = conflictoFin;
		this.conflictoTipo = conflictoTipo;
		this.claseEnConflictoId = claseEnConflictoId;
		this.conflictoConsultorioId = conflictoConsultorioId;
	}

	/** Choca con un turno de la misma persona, quiza de otra sede. */
	public static RecursoOcupadoException porTurno(
			String recurso, long turnoId, long consultorioId, boolean enOtraSede, Instant inicio, Instant fin) {
		return new RecursoOcupadoException(
				"El " + recurso + " ya tiene otro turno" + (enOtraSede ? " en otra sede" : "")
						+ " que se cruza con el intervalo pedido",
				recurso, turnoId, inicio, fin, TURNO, null, consultorioId);
	}

	/** Choca con una clase; {@code enOtraSede} solo cambia el texto del detalle. */
	public static RecursoOcupadoException porClase(
			String recurso, long claseId, long consultorioId, boolean enOtraSede, Instant inicio, Instant fin) {
		return new RecursoOcupadoException(
				"El " + recurso + " tiene una clase" + (enOtraSede ? " en otra sede" : "")
						+ " que se cruza con el intervalo pedido",
				recurso, null, inicio, fin, CLASE, claseId, consultorioId);
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

	/** {@link #TURNO}, {@link #CLASE} o {@code null} si no se sabe con que choca (el box). */
	public String getConflictoTipo() {
		return conflictoTipo;
	}

	public Long getClaseEnConflictoId() {
		return claseEnConflictoId;
	}

	public Long getConflictoConsultorioId() {
		return conflictoConsultorioId;
	}
}
