package com.akine.clinical.application;

import com.akine.clinical.domain.CasoProfesional;

import java.time.Instant;

/**
 * Un integrante del equipo tratante, vigente o no.
 *
 * <p>Viaja el {@code profesionalMembershipId} y <b>no el nombre</b>: el nombre es dato de
 * {@code person} y de {@code organization}, y duplicarlo aca produciria dos verdades que divergen
 * en cuanto alguien corrija un apellido mal tipeado (RN-M09-003). La pantalla lo resuelve con la
 * lista de colaboradores que ya tiene.
 *
 * @param hasta {@code null} mientras siga en el equipo. Quien salio <b>no desaparece</b>: quien
 *              trato al paciente lo trato, y borrarlo reescribiria historia
 */
public record CasoProfesionalView(
		long id,
		long profesionalMembershipId,
		String rol,
		Instant desde,
		Instant hasta,
		boolean vigente) {

	public static CasoProfesionalView de(CasoProfesional participacion) {
		return new CasoProfesionalView(
				participacion.getId(),
				participacion.getProfesionalMembershipId(),
				participacion.getRol().name(),
				participacion.getDesde(),
				participacion.getHasta(),
				participacion.estaVigente());
	}
}
