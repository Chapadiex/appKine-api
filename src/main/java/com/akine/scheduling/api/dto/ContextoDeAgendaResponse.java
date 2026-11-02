package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.ContextoDeAgendaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(
		name = "ContextoDeAgenda",
		description = "Los casos activos de una persona con sus planes y las sesiones que les quedan. "
				+ "**Nada clinico**: un caso se nombra por su numero y su prestacion.")
public record ContextoDeAgendaResponse(

		@Schema(example = "128") long personaId,

		@Schema(description = "Quien la atendio por ultimo en esta sede. Solo precarga: nunca bloquea otra eleccion.", example = "31")
		Long ultimoProfesionalId,

		List<CasoDeAgendaResponse> casos) {

	public static ContextoDeAgendaResponse de(ContextoDeAgendaView vista) {
		return new ContextoDeAgendaResponse(vista.personaId(), vista.ultimoProfesionalId(),
				vista.casos().stream().map(caso -> new CasoDeAgendaResponse(
						caso.casoId(), caso.numeroCaso(), caso.ofertaId(), caso.ofertaNombre(),
						caso.responsableId(), caso.responsableNombre(),
						caso.planes().stream().map(plan -> new PlanDeAgendaResponse(
								plan.planId(), plan.numeroPlan(), plan.frecuenciaSemanal(), plan.duracionSemanas(),
								plan.prestaciones().stream().map(item -> new SesionesDePrestacionResponse(
										item.ofertaId(), item.ofertaNombre(), item.planificadas(), item.realizadas(),
										item.programadas(), item.ausenciasQueConsumen(), item.pendientes())).toList())).toList()))
						.toList());
	}

	@Schema(name = "CasoDeAgenda")
	public record CasoDeAgendaResponse(
			@Schema(example = "9") long casoId,
			@Schema(example = "2") int numeroCaso,
			@Schema(example = "42") Long ofertaId,
			@Schema(example = "Kinesiologia") String ofertaNombre,
			@Schema(description = "Profesional responsable vigente: el que se ofrece por defecto", example = "31") Long responsableId,
			@Schema(example = "Gomez, Laura") String responsableNombre,
			List<PlanDeAgendaResponse> planes) {
	}

	@Schema(name = "PlanDeAgenda", description = "Un plan activo del caso.")
	public record PlanDeAgendaResponse(
			@Schema(example = "57") long planId,
			@Schema(example = "1") int numeroPlan,
			@Schema(description = "Sugerida: precarga la programacion multiple, no agenda nada", example = "3") Integer frecuenciaSemanal,
			@Schema(example = "4") Integer duracionSemanas,
			@Schema(description = "Solo las de esta sede") List<SesionesDePrestacionResponse> prestaciones) {
	}

	@Schema(name = "SesionesDePrestacion", description = "Cuantas sesiones de una prestacion quedan por programar.")
	public record SesionesDePrestacionResponse(
			@Schema(example = "42") long ofertaId,
			@Schema(example = "Kinesiologia") String ofertaNombre,
			@Schema(example = "10") int planificadas,
			@Schema(description = "Sesiones cerradas con el paciente presente", example = "3") int realizadas,
			@Schema(description = "Turnos vivos sin atencion cerrada", example = "2") int programadas,
			@Schema(description = "Ausencias marcadas como que consumen una sesion", example = "0") int ausenciasQueConsumen,
			@Schema(description = "Lo que queda por programar. Es el tope que aplica la reserva.", example = "5") int pendientes) {
	}
}
