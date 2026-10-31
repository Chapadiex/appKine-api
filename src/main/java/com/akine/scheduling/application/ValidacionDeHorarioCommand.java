package com.akine.scheduling.application;

import java.time.Instant;

/**
 * Que horario se quiere reservar y para que.
 *
 * @param profesionalId   {@code null} = cualquier habilitado
 * @param turnoExcluidoId el turno que se esta moviendo: no choca contra si mismo
 */
public record ValidacionDeHorarioCommand(
		long ofertaId,
		Instant inicio,
		Long profesionalId,
		Long espacioId,
		Long personaId,
		Long casoId,
		Long planId,
		Long turnoExcluidoId) {
}
