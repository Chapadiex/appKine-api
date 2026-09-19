package com.akine.clinical.api.dto;

import com.akine.clinical.application.CasoProfesionalView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un integrante del equipo tratante, tal como sale por la API.
 *
 * <p>Viaja el {@code profesionalMembershipId} y <b>no el nombre</b>: el nombre vive en
 * {@code person} y en {@code organization}, y duplicarlo aca produciria dos verdades que divergen
 * en cuanto alguien corrija un apellido mal tipeado (RN-M09-003). La pantalla lo resuelve con la
 * lista de colaboradores que ya tiene cargada.
 */
@Schema(name = "CasoProfesional",
		description = "Participacion de un profesional en un caso, vigente o historica")
public record CasoProfesionalResponse(

		@Schema(example = "904")
		long id,

		@Schema(description = "Membership del profesional en este centro", example = "31")
		long profesionalMembershipId,

		@Schema(allowableValues = {"RESPONSABLE", "TRATANTE"}, example = "TRATANTE")
		String rol,

		@Schema(description = "Instante UTC en que se incorporo al equipo")
		Instant desde,

		@Schema(description = "Instante UTC en que dejo el equipo. Ausente mientras siga. QUIEN "
				+ "SALIO NO DESAPARECE: trato al paciente, y borrarlo reescribiria historia")
		Instant hasta,

		@Schema(example = "true")
		boolean vigente) {

	public static CasoProfesionalResponse from(CasoProfesionalView view) {
		return new CasoProfesionalResponse(
				view.id(),
				view.profesionalMembershipId(),
				view.rol(),
				view.desde(),
				view.hasta(),
				view.vigente());
	}
}
