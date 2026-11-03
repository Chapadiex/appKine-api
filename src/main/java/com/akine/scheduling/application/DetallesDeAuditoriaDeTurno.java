package com.akine.scheduling.application;

import com.akine.scheduling.domain.Turno;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Los {@code details} que todo evento de turno deja en {@code audit_event}: quien es el paciente, con
 * quien, en que box y a que hora. Uno solo para reserva, confirmacion, cancelacion, ausencia,
 * reprogramacion y traslado, asi que "ver los cambios de la agenda de un profesional" (checklist §12.5)
 * puede filtrar por {@code profesionalMembershipId} en todos.
 *
 * <p>Sin datos clinicos y sin nombres: ids e instantes, que es lo que ya expone la agenda a quien la
 * gestiona.
 */
final class DetallesDeAuditoriaDeTurno {

	private DetallesDeAuditoriaDeTurno() {
	}

	static Map<String, String> de(Turno turno) {
		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("personaId", String.valueOf(turno.getPersonaId()));
		detalles.put("ofertaId", String.valueOf(turno.getOfertaId()));
		detalles.put("inicio", String.valueOf(turno.getInicio()));
		if (turno.getProfesionalMembershipId() != null) {
			detalles.put("profesionalMembershipId", String.valueOf(turno.getProfesionalMembershipId()));
		}
		if (turno.getEspacioId() != null) {
			detalles.put("espacioId", String.valueOf(turno.getEspacioId()));
		}
		if (turno.getTurnoOrigenId() != null) {
			detalles.put("turnoOrigenId", String.valueOf(turno.getTurnoOrigenId()));
		}
		return detalles;
	}
}
