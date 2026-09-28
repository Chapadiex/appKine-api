package com.akine.activity.application;

import com.akine.activity.domain.AsistenciaActividad;

import java.time.Instant;

/**
 * El hecho de que una persona estuvo —o no— en una clase, tal como sale del backend.
 *
 * <p><b>No lleva nombre ni documento</b>: esta proyeccion la devuelven las operaciones de
 * escritura, que ya saben a quien marcaron. Los datos de persona viajan solo en
 * {@link ParticipanteOperativoView}, detras de {@code inscripcion:read}.
 *
 * @param observaciones nota <b>operativa, no clinica</b>. La ve cualquiera con
 *                      {@code inscripcion:read}, que es precisamente por que no puede llevar
 *                      informacion de salud (RNF-M28-002)
 * @param correcciones  cuantas veces se corrigio. El detalle esta en el historial
 */
public record AsistenciaView(
		long id,
		long claseId,
		long personaId,
		long inscripcionId,
		String resultado,
		String origen,
		Long profesionalMembershipId,
		String observaciones,
		Instant registradaEn,
		Instant corregidaEn,
		int correcciones,
		long version) {

	public static AsistenciaView de(AsistenciaActividad asistencia) {
		return new AsistenciaView(
				asistencia.getId(),
				asistencia.getClaseId(),
				asistencia.getPersonaId(),
				asistencia.getInscripcionId(),
				asistencia.getResultado().name(),
				asistencia.getOrigen().name(),
				asistencia.getProfesionalMembershipId(),
				asistencia.getObservaciones(),
				asistencia.getRegistradaEn(),
				asistencia.getCorregidaEn(),
				asistencia.getCorrecciones(),
				asistencia.getVersion());
	}
}
