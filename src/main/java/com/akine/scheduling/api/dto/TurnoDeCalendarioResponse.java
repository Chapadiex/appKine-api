package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.TurnoDeCalendarioView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Un turno del calendario de la sede (DP-45): el turno de la recepcion y el nombre del profesional.
 */
@Schema(
		name = "TurnoDeCalendario",
		description = "Un turno del calendario de la sede: el turno de la recepcion (PHI minima, "
				+ "nada clinico) y el nombre del profesional.")
public record TurnoDeCalendarioResponse(

		@Schema(description = "El turno, con paciente, oferta, espacio, recepcion, serie y cobertura ya resueltos")
		TurnoDelDiaResponse turno,

		@Schema(
				description = "Nombre del profesional (`turno.profesionalId` es su membership). "
						+ "Ausente si el turno no tiene profesional o su cuenta no se resuelve.",
				example = "Gomez, Laura")
		String profesionalNombre,

		@Schema(
				description = "Solo en la agenda operativa: `true` si el turno sigue reservado o confirmado "
						+ "y ya no cae dentro de la disponibilidad efectiva de su profesional (un bloqueo o "
						+ "un cambio de horario lo dejo afuera): **pendiente de reubicacion**. Se calcula al "
						+ "leer y no se guarda. Ausente si no se evaluo (fuera de la agenda operativa, o un "
						+ "turno sin profesional).")
		Boolean fueraDeDisponibilidad) {

	public static TurnoDeCalendarioResponse de(TurnoDeCalendarioView vista) {
		return new TurnoDeCalendarioResponse(
				TurnoDelDiaResponse.de(vista.turno()), vista.profesionalNombre(), vista.fueraDeDisponibilidad());
	}
}
