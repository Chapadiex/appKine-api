package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.OperativoDeTurnoView;
import io.swagger.v3.oas.annotations.media.Schema;

/** Ver {@link OperativoDeTurnoView}. */
@Schema(
		name = "OperativoDeTurno",
		description = "Lo que la agenda muestra de un turno ademas de la reserva. PHI minima: del caso "
				+ "viaja el numero y del plan, que sesion es.")
public record OperativoDeTurnoResponse(

		@Schema(
				description = "Foto combinada de reserva, recepcion y atencion. **Se deriva al leer**: no "
						+ "es un estado guardado y no reemplaza a ninguna de las tres maquinas (DP-05).",
				allowableValues = {"RESERVADO", "CONFIRMADO", "PRESENTE", "RETIRADO", "EN_ATENCION", "ATENDIDO",
						"AUSENTE", "CANCELADO"},
				example = "PRESENTE")
		String estadoOperativo,

		@Schema(description = "Caso clinico para el que se reservo. Ausente en una primera consulta.", example = "9")
		Long casoId,

		@Schema(description = "Numero del caso dentro de la historia", example = "2")
		Integer numeroCaso,

		@Schema(description = "Plan de tratamiento para el que se reservo", example = "57")
		Long planId,

		@Schema(description = "Que sesion de su prestacion es este turno dentro del plan", example = "4")
		Integer sesionNumero,

		@Schema(description = "Cuantas sesiones planifica el plan para esa prestacion", example = "10")
		Integer sesionesPlanificadas,

		@Schema(description = "`true` si se reservo sobre un horario ocupado, con autorizacion")
		boolean sobreturno,

		@Schema(description = "Por que se autorizo el sobreturno", example = "Urgencia clinica")
		String sobreturnoMotivo,

		@Schema(description = "Nombre del profesional. Ausente si el turno no tiene o no se resuelve.", example = "Gomez, Laura")
		String profesionalNombre,

		@Schema(description = "Nombre del box asignado", example = "Box 3")
		String espacioNombre,

		@Schema(description = "La atencion del turno, si ya empezo", example = "501")
		Long sesionId,

		@Schema(
				description = "A quien se le atribuye la cancelacion",
				allowableValues = {"PACIENTE", "PROFESIONAL", "INSTITUCION"})
		String origenCancelacion,

		@Schema(description = "Si la ausencia consume una sesion del plan. Ausente si no se decidio.")
		Boolean ausenciaConsumeSesion) {

	public static OperativoDeTurnoResponse de(OperativoDeTurnoView vista) {
		return vista == null ? null : new OperativoDeTurnoResponse(
				vista.estadoOperativo(), vista.casoId(), vista.numeroCaso(), vista.planId(),
				vista.sesionNumero(), vista.sesionesPlanificadas(), vista.sobreturno(), vista.sobreturnoMotivo(),
				vista.profesionalNombre(), vista.espacioNombre(), vista.sesionId(),
				vista.origenCancelacion(), vista.ausenciaConsumeSesion());
	}
}
