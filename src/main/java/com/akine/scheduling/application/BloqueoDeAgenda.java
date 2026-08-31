package com.akine.scheduling.application;

import com.akine.scheduling.domain.AgendaSede;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.AgendaSedeRepositoryPort;

/**
 * Toma el lock que serializa las reservas de una sede.
 *
 * <p><b>La fila tiene que existir antes.</b> La crea {@link AgendaSedeIniciador} en una transaccion
 * aparte, y no aca: crearla dentro de la transaccion de la reserva produce un DEADLOCK entre las
 * primeras N reservas concurrentes de una sede, no una violacion de unique que una pierda
 * limpiamente. El detalle esta en el javadoc de esa clase, y lo encontro
 * {@code TurnoConcurrenteIT} contra MySQL real.
 */
final class BloqueoDeAgenda {

	private BloqueoDeAgenda() {
		// Utilidad de concurrencia.
	}

	static AgendaSede tomar(
			AgendaSedeRepositoryPort agendas, long organizationId, long consultorioId) {

		return agendas.lockByScope(organizationId, consultorioId)
				.orElseThrow(() -> new IllegalStateException(
						"La agenda de la sede " + consultorioId + " no existe al tomar el lock. "
								+ "AgendaSedeIniciador#asegurar tiene que correr antes, y en su "
								+ "propia transaccion."));
	}
}
