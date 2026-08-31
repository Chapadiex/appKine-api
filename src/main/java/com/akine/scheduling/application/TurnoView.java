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
 * @param version para el control optimista de las mutaciones que llegan en 05.03
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
				turno.getVersion());
	}
}
