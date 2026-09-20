package com.akine.scheduling.infrastructure;

import com.akine.scheduling.application.AgendaSedeIniciador;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.spi.AgendaDeSede;
import com.akine.scheduling.spi.OcupacionDeAgenda;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Adaptador de {@link AgendaDeSede}. Le presta a {@code activity} el lock de {@code agenda_sede} y
 * las lecturas de ocupacion de turnos.
 *
 * <h2>Por que la fila se presta en vez de duplicarse</h2>
 *
 * <p>Ver la cabecera de {@link AgendaDeSede}: dos puntos de serializacion no se ven entre si, asi
 * que una clase y un turno ganarian los dos el mismo box sin que nada falle. Esto no es un atajo
 * hacia {@code scheduling}: es la unica forma de que la exclusion exista.
 *
 * <p>{@code activity} sigue sin ver la entidad {@code AgendaSede} ni el repositorio: ve este
 * contrato y nada mas.
 *
 * <h2>El discriminador</h2>
 *
 * <p>Todo lo que sale de aca viaja con {@code tipo = "TURNO"}. No es decoracion: quien pregunta
 * esta componiendo una grilla donde conviven varias clases de evento, y un id sin tipo no
 * identifica nada —un turno 7 y una clase 7 existen a la vez—.
 */
@Component
public class SchedulingAgendaDeSede implements AgendaDeSede {

	/** Discriminador de los eventos de M12 en la agenda unificada. */
	public static final String TIPO_TURNO = "TURNO";

	private final AgendaSedeRepository agendas;
	private final TurnoRepository turnos;
	private final AgendaSedeIniciador iniciador;

	public SchedulingAgendaDeSede(
			AgendaSedeRepository agendas, TurnoRepository turnos, AgendaSedeIniciador iniciador) {

		this.agendas = agendas;
		this.turnos = turnos;
		this.iniciador = iniciador;
	}

	/**
	 * Delega en {@link AgendaSedeIniciador}, que es quien lleva el {@code REQUIRES_NEW}.
	 *
	 * <p>No se reimplementa el {@code INSERT ... ON DUPLICATE KEY UPDATE} aca: una segunda copia
	 * de ese patron es una segunda copia que envejece, y el defecto que evita —deadlock entre las
	 * primeras N escrituras de una sede— no se manifiesta en ningun test unitario.
	 */
	@Override
	public void asegurar(long organizationId, long consultorioId) {
		iniciador.asegurar(organizationId, consultorioId);
	}

	@Override
	public void bloquear(long organizationId, long consultorioId) {
		agendas.lockByScope(organizationId, consultorioId)
				.orElseThrow(() -> new IllegalStateException(
						"La agenda de la sede " + consultorioId + " no existe al tomar el lock. "
								+ "AgendaDeSede#asegurar tiene que correr antes, y en su propia "
								+ "transaccion."));
	}

	@Override
	public List<OcupacionDeAgenda> turnosDeProfesionalQueCruzan(
			long organizationId, long profesionalMembershipId, Instant inicio, Instant fin) {

		return proyectar(turnos.findVivosDeProfesionalQueCruzan(
				organizationId, profesionalMembershipId, inicio, fin));
	}

	@Override
	public List<OcupacionDeAgenda> turnosDeEspacioQueCruzan(
			long organizationId, long espacioId, Instant inicio, Instant fin) {

		return proyectar(turnos.findVivosDeEspacioQueCruzan(
				organizationId, espacioId, inicio, fin));
	}

	private static List<OcupacionDeAgenda> proyectar(List<Turno> turnos) {
		return turnos.stream()
				.map(turno -> new OcupacionDeAgenda(
						TIPO_TURNO,
						turno.getId(),
						turno.getProfesionalMembershipId(),
						turno.getEspacioId(),
						turno.getInicio(),
						turno.getFin()))
				.toList();
	}
}
