package com.akine.scheduling.infrastructure;

import com.akine.resource.spi.DisponibilidadImpactProbe;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Implementacion de {@code resource.spi.DisponibilidadImpactProbe} sobre la tabla {@code turno}
 * (paquete E-1, reescrita en A-11).
 *
 * <h2>Que devuelve, y que NO decide</h2>
 *
 * <p>Los turnos pendientes de la sede —de un profesional, o de todos si la pregunta viene de una
 * excepcion de alcance sede— que <b>empiezan</b> dentro de la ventana que {@code resource}
 * calcula. No decide cuales quedan en conflicto: eso lo hace {@code resource} comparando la
 * disponibilidad efectiva antes y despues del cambio, porque es el unico modulo que conoce las
 * reglas. Hasta A-11 esta clase devolvia la cuenta de la ventana, y {@code resource} la informaba
 * como cota superior; ver el javadoc de la interfaz.
 *
 * <p>Ni nombre del paciente ni oferta: id, profesional e intervalo.
 */
@Component
public class DisponibilidadImpactoSobreTurnos implements DisponibilidadImpactProbe {

	private final TurnoRepositoryPort turnos;

	public DisponibilidadImpactoSobreTurnos(TurnoRepositoryPort turnos) {
		this.turnos = turnos;
	}

	@Override
	public List<TurnoPendiente> pendientesEn(
			long organizationId, long consultorioId, Long membershipId, Instant desde, Instant hasta) {

		if (!hasta.isAfter(desde)) {
			return List.of();
		}
		List<Turno> pendientes = membershipId == null
				? turnos.findPendientesEnLaSede(organizationId, consultorioId, desde, hasta)
				: turnos.findPendientesDelProfesionalEnLaSede(
						organizationId, consultorioId, membershipId, desde, hasta);
		return pendientes.stream()
				.map(turno -> new TurnoPendiente(
						turno.getId(), turno.getProfesionalMembershipId(), turno.getInicio(), turno.getFin()))
				.toList();
	}
}
