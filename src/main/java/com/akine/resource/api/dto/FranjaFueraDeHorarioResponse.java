package com.akine.resource.api.dto;

import com.akine.resource.application.FranjaFueraDeHorario;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonSerialize;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Una franja de un profesional que el horario nuevo de la sede deja afuera (regla del 08/11/2026).
 * Viaja en el aviso —el 409 {@code franjas-de-profesionales-fuera-del-horario}, sin {@code accion}— y
 * en la respuesta del {@code PUT} que la resolvio, con {@code accion}.
 */
@Schema(description = "Franja de un profesional que el horario nuevo de la sede deja afuera, total o "
		+ "parcialmente")
public record FranjaFueraDeHorarioResponse(

		@Schema(description = "Bloque de disponibilidad del profesional", example = "12")
		long bloqueId,

		@Schema(description = "Vinculo del profesional en la organizacion; abre su horario semanal "
				+ "(/horarios?membershipId=...)", example = "7")
		long membershipId,

		@Schema(description = "Nombre para mostrar del profesional. Ausente si no se pudo resolver",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		String profesionalNombre,

		@Schema(description = "Dia de la semana ISO-8601: 1 = lunes .. 7 = domingo", example = "6")
		int diaSemana,

		@Schema(description = "Hora local de inicio actual de la franja", type = "string", example = "08:00")
		@JsonSerialize(using = HoraDelDia.Serializador.class)
		LocalTime horaDesde,

		@Schema(description = "Hora local de fin actual, EXCLUSIVA. La medianoche viaja como 24:00",
				type = "string", example = "14:00")
		@JsonSerialize(using = HoraDelDia.Serializador.class)
		LocalTime horaHasta,

		@Schema(description = "Inicio de vigencia de la franja", example = "2026-11-01")
		LocalDate vigenciaDesde,

		@Schema(description = "Fin de vigencia, EXCLUSIVO. Ausente: sin fin previsto",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta,

		@Schema(description = "Lo que queda de la franja dentro del horario nuevo. Vacia: queda entera "
				+ "afuera y recortarla la da de baja. Mas de un tramo: la sede cierra en el medio de la "
				+ "franja y recortarla la parte en dos")
		List<IntervaloHorarioResponse> resultantes,

		@Schema(description = "Que se hizo con la franja. Ausente en el aviso, antes de decidir",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		FranjaFueraDeHorario.AccionSobreFranja accion) {

	public static FranjaFueraDeHorarioResponse from(FranjaFueraDeHorario franja) {
		return new FranjaFueraDeHorarioResponse(
				franja.bloqueId(),
				franja.membershipId(),
				franja.profesionalNombre(),
				franja.diaSemana(),
				franja.horaDesde(),
				franja.horaHasta(),
				franja.vigenciaDesde(),
				franja.vigenciaHasta(),
				franja.resultantes().stream().map(IntervaloHorarioResponse::from).toList(),
				franja.accion());
	}
}
