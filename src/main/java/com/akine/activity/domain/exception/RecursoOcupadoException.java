package com.akine.activity.domain.exception;

import com.akine.platform.spi.problem.ConflictoDeAgenda;

import java.time.Instant;

/**
 * El profesional o el espacio ya estan tomados en ese intervalo, por un turno o por otra clase.
 *
 * <p><b>Reusa el tipo transversal {@code recurso-ocupado}</b>, el mismo que publica M12. Para la
 * pantalla el desenlace es identico —ese recurso esta ocupado en ese horario— y que el evento
 * conflictivo sea un turno o una clase no cambia lo que puede hacer el usuario. Publicar un
 * segundo codigo obligaria al cliente a manejar dos para un mismo caso.
 *
 * <p>Lo que si distingue es {@code recurso} ("profesional" o "espacio"): esos dos si llevan a
 * acciones distintas. Desde la agenda de clases y avisos (0.156.0), cuando choca con un evento de la
 * misma persona viajan ademas su tipo ({@code TURNO} o {@code CLASE}), su id, su sede y su horario:
 * la otra sede tiene que poder nombrarse en el mensaje.
 */
public class RecursoOcupadoException extends RuntimeException {

	private final String recurso;
	private final String conflictoTipo;
	private final Long conflictoId;
	private final Long conflictoConsultorioId;
	private final Instant conflictoInicio;
	private final Instant conflictoFin;
	private final ConflictoDeAgenda conflicto;

	public RecursoOcupadoException(String recurso) {
		super("El " + recurso + " ya esta ocupado en ese horario");
		this.recurso = recurso;
		this.conflictoTipo = null;
		this.conflictoId = null;
		this.conflictoConsultorioId = null;
		this.conflictoInicio = null;
		this.conflictoFin = null;
		this.conflicto = null;
	}

	/**
	 * Choca con un evento identificado de la misma persona.
	 *
	 * @param tipo       {@code TURNO} o {@code CLASE}
	 * @param enOtraSede solo cambia el texto del detalle
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public RecursoOcupadoException(
			String recurso, String tipo, long conflictoId, Long conflictoConsultorioId, boolean enOtraSede,
			Instant conflictoInicio, Instant conflictoFin) {
		super("El " + recurso + " tiene " + ("CLASE".equals(tipo) ? "otra clase" : "un turno")
				+ (enOtraSede ? " en otra sede" : "") + " que se cruza con ese horario");
		this.recurso = recurso;
		this.conflictoTipo = tipo;
		this.conflictoId = conflictoId;
		this.conflictoConsultorioId = conflictoConsultorioId;
		this.conflictoInicio = conflictoInicio;
		this.conflictoFin = conflictoFin;
		this.conflicto = null;
	}

	/**
	 * Disponibilidad central (0.158.0): el conflicto real de un instructor (principal o adicional), con
	 * causa, sede y franja, y el detail en castellano. Nunca datos del paciente.
	 */
	public RecursoOcupadoException(String recurso, ConflictoDeAgenda conflicto) {
		super(conflicto.detalle("No es posible asignarle la clase en ese horario."));
		this.recurso = recurso;
		this.conflictoTipo = conflicto.tipo().name();
		this.conflictoId = conflicto.eventoId();
		this.conflictoConsultorioId = conflicto.consultorioId();
		this.conflictoInicio = conflicto.desde();
		this.conflictoFin = conflicto.hasta();
		this.conflicto = conflicto;
	}

	/** {@code null} en las formas anteriores a 0.158.0. */
	public ConflictoDeAgenda getConflicto() {
		return conflicto;
	}

	public String getRecurso() {
		return recurso;
	}

	public String getConflictoTipo() {
		return conflictoTipo;
	}

	public Long getConflictoId() {
		return conflictoId;
	}

	public Long getConflictoConsultorioId() {
		return conflictoConsultorioId;
	}

	public Instant getConflictoInicio() {
		return conflictoInicio;
	}

	public Instant getConflictoFin() {
		return conflictoFin;
	}
}
