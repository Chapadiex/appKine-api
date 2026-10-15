package com.akine.clinical.api.dto;

import com.akine.clinical.application.CasoProfesionalView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un integrante del equipo tratante, tal como sale por la API.
 *
 * <p>Viaja el {@code profesionalMembershipId} y, desde 0.107.0, el nombre de la cuenta
 * <b>resuelto al leer</b> (pedido de appKine-web #83). No se guarda en el caso: el nombre vive en
 * la cuenta, y copiarlo produciria dos verdades que divergen en cuanto alguien corrija un apellido.
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
		boolean vigente,

		@Schema(description = "Nombre del profesional, resuelto al leer por su membership en la "
				+ "organizacion (0.107.0): sirve aunque el caso sea de otra sede o el profesional ya "
				+ "no este habilitado. Ausente si no resuelve o en la vista administrativa de la "
				+ "historia; ahi la pantalla muestra el id", example = "Lucia Fernandez")
		String profesionalNombre) {

	public static CasoProfesionalResponse from(CasoProfesionalView view) {
		return new CasoProfesionalResponse(
				view.id(),
				view.profesionalMembershipId(),
				view.rol(),
				view.desde(),
				view.hasta(),
				view.vigente(),
				view.profesionalNombre());
	}
}
