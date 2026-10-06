package com.akine.scheduling.infrastructure;

import com.akine.resource.spi.EspacioOccupancyProbe;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Implementacion de {@code resource.spi.EspacioOccupancyProbe} sobre la tabla {@code turno}
 * (paquete E-1).
 *
 * <p>Con ella empiezan a emitirse los dos codigos que 02.02 reservo: dar de baja un box con
 * turnos pendientes responde {@code 409 espacio-has-active-references}, y bajar la capacidad por
 * debajo del pico comprometido responde {@code 409 espacio-capacity-below-occupancy}.
 *
 * <h2>Un PICO, no un total</h2>
 *
 * <p>La interfaz pide la maxima cantidad de lugares comprometidos <b>simultaneamente</b>. Cada
 * turno es una persona, asi que dos turnos consecutivos en el mismo box ocupan un lugar y no dos,
 * y los ocho turnos de una misma franja grupal ocupan ocho. Se calcula con un barrido de eventos:
 * {@code +1} en cada inicio, {@code -1} en cada fin, y a igual instante el fin va primero, porque
 * los extremos superiores son exclusivos —un turno que termina a las 10 no se cruza con el que
 * empieza a las 10—. Es la misma convencion que {@code Turno#seCruzaCon}.
 *
 * <p>Se lee la lista y se barre en memoria en vez de resolverlo en SQL: el pico de intervalos no
 * se expresa en un {@code GROUP BY}, y los turnos pendientes de un box son decenas.
 *
 * <p>No abre transaccion propia: se une a la de {@code EspacioService}, que ya bloqueo la fila
 * del espacio con {@code FOR UPDATE}.
 */
@Component
public class EspacioOcupadoPorTurnos implements EspacioOccupancyProbe {

	/** Vocabulario estable: viaja al cliente en el Problem Details. Sin datos de personas. */
	static final String TIPO = "turnos-futuros";

	private final TurnoRepositoryPort turnos;

	public EspacioOcupadoPorTurnos(TurnoRepositoryPort turnos) {
		this.turnos = turnos;
	}

	@Override
	public Occupancy peakOccupancyFrom(long organizationId, long espacioId, Instant at) {
		long pico = picoSimultaneo(turnos.findPendientesDelEspacio(organizationId, espacioId, at));
		return pico == 0 ? Occupancy.ninguna() : new Occupancy(TIPO, pico);
	}

	/** Maximo de turnos que se cruzan en un mismo instante. */
	static long picoSimultaneo(List<Turno> pendientes) {
		List<Evento> eventos = new ArrayList<>(pendientes.size() * 2);
		for (Turno turno : pendientes) {
			eventos.add(new Evento(turno.getInicio(), +1));
			eventos.add(new Evento(turno.getFin(), -1));
		}
		// A igual instante, -1 antes que +1: el fin es exclusivo.
		eventos.sort(Comparator.comparing(Evento::instante).thenComparingInt(Evento::delta));

		long actual = 0;
		long pico = 0;
		for (Evento evento : eventos) {
			actual += evento.delta();
			pico = Math.max(pico, actual);
		}
		return pico;
	}

	private record Evento(Instant instante, int delta) {
	}
}
