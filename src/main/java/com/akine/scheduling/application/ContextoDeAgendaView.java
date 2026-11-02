package com.akine.scheduling.application;

import java.util.List;

/**
 * Lo que hace falta para programarle un turno a una persona (TURNO - Tecnico §9): sus casos activos,
 * con sus planes y cuantas sesiones le quedan a cada uno.
 *
 * @param ultimoProfesionalId quien la atendio por ultimo en esta sede. Solo ordena y precarga: nunca
 *                            bloquea otra eleccion (TURNO.md §40)
 */
public record ContextoDeAgendaView(long personaId, Long ultimoProfesionalId, List<CasoDeAgendaView> casos) {

	public record CasoDeAgendaView(
			long casoId,
			int numeroCaso,
			Long ofertaId,
			String ofertaNombre,
			Long responsableId,
			String responsableNombre,
			List<PlanDeAgendaView> planes) {
	}

	public record PlanDeAgendaView(
			long planId,
			int numeroPlan,
			Integer frecuenciaSemanal,
			Integer duracionSemanas,
			List<SesionesDePrestacionView> prestaciones) {
	}

	/** Solo las prestaciones de esta sede: una oferta de otra no se puede reservar aca. */
	public record SesionesDePrestacionView(
			long ofertaId,
			String ofertaNombre,
			int planificadas,
			int realizadas,
			int programadas,
			int ausenciasQueConsumen,
			int pendientes) {
	}
}
