package com.akine.scheduling.infrastructure;

import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.ReservaProbe;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cuenta las reservas vivas por instante de inicio, sobre la tabla {@code turno}.
 *
 * <h2>Por que existe, y que estaba roto sin ella</h2>
 *
 * <p>AKINE-05.01 declaro {@link ReservaProbe} como costura y dejo una implementacion que devolvia
 * el mapa vacio, porque los turnos no existian todavia. <b>05.02 creo los turnos y no la
 * reemplazo</b>, asi que la agenda siguio ofreciendo huecos ya vendidos: el usuario elegia un
 * horario que la pantalla mostraba libre y se comia un 409 al confirmar.
 *
 * <p>No corrompia nada —la reserva revalida bajo el lock de la sede y una sola gana— pero convertia
 * un caso normal en un error, que es exactamente lo que el motor de slots existe para evitar.
 *
 * <p>La leccion, y por eso queda escrita: <b>una costura con una implementacion provisoria no avisa
 * cuando le llega el momento de ser reemplazada.</b> El javadoc de la implementacion vieja decia
 * "se borra en 05.02" y nadie la borro; lo unico que lo destapo fue leer el codigo buscando otra
 * cosa.
 *
 * <h2>Una consulta por slot, y por que alcanza</h2>
 *
 * <p>Se consulta el cupo por instante de inicio, que es la unica clave que el motor y la agenda
 * comparten sin ambiguedad —los slots no se persisten y no tienen id—. Una consulta agrupada por
 * hora seria mas barata, pero el conteo tiene que respetar el mismo predicado de vivo que usa la
 * reserva, y duplicar ese predicado en un {@code GROUP BY} es exactamente el lugar donde las dos
 * versiones se separan sin que nada falle.
 */
@Component
public class ReservaProbeSobreTurnos implements ReservaProbe {

	private final TurnoRepositoryPort turnos;

	public ReservaProbeSobreTurnos(TurnoRepositoryPort turnos) {
		this.turnos = turnos;
	}

	@Override
	public Map<Instant, Integer> reservasPorInicio(
			long organizationId,
			long consultorioId,
			long ofertaId,
			List<Long> recursoIds,
			Instant desde,
			Instant hasta) {

		Map<Instant, Integer> porInicio = new HashMap<>();
		for (Turno turno : turnos.findVivosDeLaOfertaEnVentana(organizationId, ofertaId, desde, hasta)) {
			porInicio.merge(turno.getInicio(), 1, Integer::sum);
		}
		return porInicio;
	}
}
