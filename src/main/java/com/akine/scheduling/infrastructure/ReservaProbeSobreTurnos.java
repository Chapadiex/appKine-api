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
 * <h2>Una consulta por ventana, y el agrupado en memoria</h2>
 *
 * <p>Se consulta el cupo por instante de inicio, que es la unica clave que el motor y la agenda
 * comparten sin ambiguedad —los slots no se persisten y no tienen id—. Se lee UNA vez por dia de agenda y se agrupa en
 * memoria. Un {@code COUNT ... GROUP BY} en la base seria mas barato, pero el conteo tiene que respetar el mismo predicado de vivo que usa la
 * reserva, y duplicar ese predicado en un {@code GROUP BY} es exactamente el lugar donde las dos
 * versiones se separan sin que nada falle.
 *
 * <h2>Cancelado no cuenta, ausente si</h2>
 *
 * <p>Cancelar hace baja logica y sella {@code deleted_at} (RN-M12-002), asi que el predicado de
 * vivo devuelve el hueco a la grilla sin borrar nada. Un turno con paciente ausente conserva
 * {@code deleted_at} nulo: ocupo el lugar y lo sigue ocupando.
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
			if (ocupaAlgunRecurso(turno, recursoIds)) {
				porInicio.merge(turno.getInicio(), 1, Integer::sum);
			}
		}
		return porInicio;
	}

	/**
	 * {@code true} si el turno cuenta para el filtro pedido.
	 *
	 * <p>La lista vacia significa "sin filtro" y no "ningun recurso" — es la misma convencion que
	 * las habilitaciones de 02.07, donde una lista vacia habilita a TODOS. Invertirla aca haria que
	 * el motor no descontara nada, que es justamente el defecto que esta clase vino a cerrar.
	 *
	 * <p>Se compara contra el profesional y contra el espacio porque el {@code spi} declara que la
	 * lista puede traer cualquiera de los dos, segun que este agrupando el motor. Los dos son
	 * nullables —una oferta puede no exigir profesional, o no exigir espacio— y {@code List.of()}
	 * lanza {@code NullPointerException} al buscar un null, asi que el nulo se descarta antes.
	 */
	private static boolean ocupaAlgunRecurso(Turno turno, List<Long> recursoIds) {
		return recursoIds.isEmpty()
				|| contiene(recursoIds, turno.getProfesionalMembershipId())
				|| contiene(recursoIds, turno.getEspacioId());
	}

	private static boolean contiene(List<Long> recursoIds, Long recursoId) {
		return recursoId != null && recursoIds.contains(recursoId);
	}
}
