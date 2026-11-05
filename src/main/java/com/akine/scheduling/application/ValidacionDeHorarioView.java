package com.akine.scheduling.application;

import com.akine.scheduling.application.DisponibilidadView.HorarioDisponibleView;

import java.time.Instant;
import java.util.List;

/**
 * Si un horario puntual se puede reservar y, si no, por que y que hay en su lugar.
 *
 * <p><b>Es informativo</b>, igual que la agenda: dos consultas concurrentes pueden ver el mismo hueco
 * libre. Quien decide es la reserva, bajo el lock (TURNO - Tecnico §66).
 *
 * @param fin           el de quien atiende. Con "cualquier profesional" y varios libres todavia no hay
 *                      quien atienda: es el de la oferta, y el de cada uno sale de su duracion propia
 * @param estado        DISPONIBLE, NO_DISPONIBLE o DISPONIBLE_CON_AVISO
 * @param causa         por que no: PASADO, SLOT_NO_DISPONIBLE, SLOT_COMPLETO, PROFESIONAL_OCUPADO,
 *                      ESPACIO_OCUPADO, OFERTA_NO_AGENDABLE o CONTEXTO_CLINICO. {@code null} si se puede
 * @param profesionales quien puede atender a esa hora, con "cualquier profesional"
 * @param espaciosLibres para ofrecer "Cambiar" el box asignado
 * @param avisos        lo que hay que saber pero no impide reservar
 * @param alternativas  los horarios mas cercanos, si este no se puede
 * @param admiteSobreturno {@code true} si un sobreturno a esa hora entraria: lo que lo impide es solo
 *                      ocupacion de ESTA sede (turnos, cupo, box). {@code false} si choca con algo que el
 *                      sobreturno no salva (un turno de la misma persona en otra sede, una clase, un cierre,
 *                      fuera de horario) y en un horario libre. Sin mirar el permiso (D-5, 0.158.0)
 */
public record ValidacionDeHorarioView(
		String estado,
		String causa,
		String motivo,
		Instant inicio,
		Instant fin,
		List<RecursoLibreView> profesionales,
		List<RecursoLibreView> espaciosLibres,
		Long turnoEnConflictoId,
		Instant conflictoInicio,
		Instant conflictoFin,
		List<String> avisos,
		List<HorarioDisponibleView> alternativas,
		String conflictoTipo,
		Long claseEnConflictoId,
		Long conflictoConsultorioId,
		boolean admiteSobreturno) {

	/** Sin {@code admiteSobreturno} (D-5): no lo admite. */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public ValidacionDeHorarioView(
			String estado, String causa, String motivo, Instant inicio, Instant fin,
			List<RecursoLibreView> profesionales, List<RecursoLibreView> espaciosLibres, Long turnoEnConflictoId,
			Instant conflictoInicio, Instant conflictoFin, List<String> avisos,
			List<HorarioDisponibleView> alternativas, String conflictoTipo, Long claseEnConflictoId,
			Long conflictoConsultorioId) {
		this(estado, causa, motivo, inicio, fin, profesionales, espaciosLibres, turnoEnConflictoId,
				conflictoInicio, conflictoFin, avisos, alternativas, conflictoTipo, claseEnConflictoId,
				conflictoConsultorioId, false);
	}

	/** Sin el detalle de 0.156.0 (con que tipo de evento choca y en que sede). */
	@SuppressWarnings("checkstyle:ParameterNumber")
	public ValidacionDeHorarioView(
			String estado, String causa, String motivo, Instant inicio, Instant fin,
			List<RecursoLibreView> profesionales, List<RecursoLibreView> espaciosLibres, Long turnoEnConflictoId,
			Instant conflictoInicio, Instant conflictoFin, List<String> avisos,
			List<HorarioDisponibleView> alternativas) {
		this(estado, causa, motivo, inicio, fin, profesionales, espaciosLibres, turnoEnConflictoId,
				conflictoInicio, conflictoFin, avisos, alternativas, null, null, null);
	}

	/**
	 * @param duracionPropiaMinutos lo que tarda ese profesional en la oferta, si no es lo que dura la
	 *                              oferta. {@code null} en un box y en quien usa la de la oferta
	 */
	public record RecursoLibreView(long id, String nombre, Integer duracionPropiaMinutos) {

		public RecursoLibreView(long id, String nombre) {
			this(id, nombre, null);
		}
	}
}
