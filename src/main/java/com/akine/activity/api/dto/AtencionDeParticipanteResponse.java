package com.akine.activity.api.dto;

import com.akine.activity.application.AtencionDeParticipanteView;
import com.akine.encounter.spi.AtencionDeParticipante;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * La atencion clinica de un participante de clase (AKINE-08.05). Ids, estado y numeros: el
 * contenido se lee y se escribe por la Sesion.
 */
@Schema(
		name = "AtencionDeParticipante",
		description = "La Sesion de un participante de una clase clinica. Es una Sesion como "
				+ "cualquier otra: borrador, evaluacion, tratamientos, cierre y enmienda van por "
				+ "las operaciones de Sesion de la sede. No lleva contenido clinico.")
public record AtencionDeParticipanteResponse(

		@Schema(example = "412") long inscripcionId,
		@Schema(description = "La Sesion. Con ella se abre la ficha del participante", example = "501")
		long sesionId,
		@Schema(example = "7") long consultorioId,
		@Schema(example = "640") long claseId,
		@Schema(description = "La asistencia de la que cuelga la derivacion", example = "933")
		long asistenciaId,
		@Schema(example = "88") long derivacionId,
		@Schema(example = "301") long historiaClinicaId,
		@Schema(description = "El Caso de la derivacion. No se puede cambiar", example = "55")
		long casoId,
		@Schema(description = "Membership del profesional que la abrio: solo el escribe en ella",
				example = "31")
		long profesionalId,
		@Schema(allowableValues = {"BORRADOR", "CERRADA"}) String estado,
		@Schema(description = "Correlativo por Historia Clinica. Ausente mientras esta abierta",
				example = "14")
		Integer numeroSesion,
		@Schema(description = "Correlativo dentro del Caso (RN-M14-010). Ausente mientras esta "
				+ "abierta", example = "3")
		Integer numeroEnCaso,
		Instant iniciadaEn,
		Instant cerradaEn,
		@Schema(description = "La version de la Sesion, para su primer guardado", example = "0")
		long version,
		@Schema(description = "`true` si este pedido no creo nada porque la atencion ya existia")
		boolean yaExistia) {

	public static AtencionDeParticipanteResponse de(AtencionDeParticipanteView vista) {
		return de(vista.inscripcionId(), vista.atencion());
	}

	public static AtencionDeParticipanteResponse de(long inscripcionId, AtencionDeParticipante a) {
		return new AtencionDeParticipanteResponse(
				inscripcionId, a.sesionId(), a.consultorioId(), a.claseId(), a.participacionId(),
				a.derivacionId(), a.historiaClinicaId(), a.casoId(), a.profesionalMembershipId(),
				a.estado(), a.numeroSesion(), a.numeroEnCaso(), a.iniciadaEn(), a.cerradaEn(),
				a.version(), a.yaExistia());
	}
}
