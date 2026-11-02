package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.TurnoDeCalendarioView;
import io.swagger.v3.oas.annotations.media.Schema;

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
		String profesionalNombre) {

	public static TurnoDeCalendarioResponse de(TurnoDeCalendarioView vista) {
		return new TurnoDeCalendarioResponse(
				TurnoDelDiaResponse.de(vista.turno()), vista.profesionalNombre());
	}
}
