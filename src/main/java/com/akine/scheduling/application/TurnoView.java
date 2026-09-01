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
 * @param estado             RESERVADO, CONFIRMADO, CANCELADO o AUSENTE
 * @param motivoCancelacion  presente solo si el turno esta cancelado
 * @param version            para el control optimista de cancelar, mover y marcar ausencia
 */
public record TurnoView(
		long id,
		long consultorioId,
		long ofertaId,
		long personaId,
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
		long version) {

	public static TurnoView de(Turno turno) {
		return new TurnoView(
				turno.getId(),
				turno.getConsultorioId(),
				turno.getOfertaId(),
				turno.getPersonaId(),
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
				turno.getVersion());
	}
}
