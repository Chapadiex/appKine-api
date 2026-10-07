package com.akine.scheduling.application;

import com.akine.scheduling.domain.Turno;

import java.time.Instant;

/**
 * Un turno reservado, tal como lo ve quien lo reservo.
 *
 * <p>No lleva datos del paciente mas alla de su id: quien consulta un turno ya tiene o puede pedir
 * la ficha por su propio endpoint, y duplicar nombre y documento aca los expondria en toda
 * respuesta de agenda —incluida la de un rol que puede ver turnos y no fichas—.
 *
 * @param estado             RESERVADO, CONFIRMADO, CANCELADO o AUSENTE (la espera es de la recepcion)
 * @param motivoCancelacion  presente solo si el turno esta cancelado
 * @param llegadaEn          DEPRECADO desde E-4: la llegada es de la Recepcion. Solo lo completa
 *                           el check-in deprecado de 05.04 ({@link #conLlegada}); si no, {@code null}
 * @param version            para el control optimista de cancelar, mover y marcar ausencia
 */
public record TurnoView(
		long id,
		long consultorioId,
		long ofertaId,
		long personaId,
		Long serieId,
		Long profesionalId,
		Long espacioId,
		Instant inicio,
		Instant fin,
		String estado,
		Instant reservadoEn,
		Instant confirmadoEn,
		String motivoCancelacion,
		Instant canceladoEn,
		Instant ausenteEn,
		Instant reprogramadoEn,
		Instant llegadaEn,
		long version) {

	public static TurnoView de(Turno turno) {
		return new TurnoView(
				turno.getId(),
				turno.getConsultorioId(),
				turno.getOfertaId(),
				turno.getPersonaId(),
				turno.getSerieId(),
				turno.getProfesionalMembershipId(),
				turno.getEspacioId(),
				turno.getInicio(),
				turno.getFin(),
				turno.getEstado().name(),
				turno.getReservadoEn(),
				turno.getConfirmadoEn(),
				turno.getMotivoCancelacion(),
				turno.getCanceladoEn(),
				turno.getAusenteEn(),
				turno.getReprogramadoEn(),
				null,
				turno.getVersion());
	}

	/** La misma vista con la hora de llegada de la recepcion, para el check-in deprecado de 05.04. */
	public TurnoView conLlegada(Instant llegada) {
		return new TurnoView(id, consultorioId, ofertaId, personaId, serieId, profesionalId, espacioId,
				inicio, fin, estado, reservadoEn, confirmadoEn, motivoCancelacion, canceladoEn,
				ausenteEn, reprogramadoEn, llegada, version);
	}
}
