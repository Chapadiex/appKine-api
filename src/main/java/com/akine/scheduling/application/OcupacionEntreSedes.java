package com.akine.scheduling.application;

import com.akine.scheduling.application.AgendaOperativaView.ConflictoDeAgendaView;
import com.akine.scheduling.application.AgendaOperativaView.OcupacionEnOtraSedeView;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.OcupacionExternaProbe;
import com.akine.scheduling.spi.OcupacionExternaProbe.OcupacionExterna;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lo que tiene ocupada a cada persona de la agenda de una sede <b>fuera</b> de esa sede, y los choques
 * turno–clase que ya existen en la base (agenda de clases y avisos, contrato 0.156.0).
 *
 * <h2>Por que existe</h2>
 *
 * <p>La agenda operativa dibuja las celdas libres con las franjas, los turnos y las clases <b>de la
 * sede</b>. La misma persona tiene una membership por sede, y lo que hace en otra sede no aparecia:
 * la celda se veia libre y la reserva despues la rechazaba. {@link #ocupacionesEnOtrasSedes} devuelve
 * esos intervalos, sin paciente ni id —son de otra sede, y la pantalla solo necesita saber que no
 * esta libre—.
 *
 * <h2>Los conflictos heredados</h2>
 *
 * <p>Hasta 0.156.0 una clase no miraba los turnos de la misma persona en otra sede, ni los instructores
 * adicionales, y un turno no miraba las clases de otra sede: puede haber datos que ya se pisan. <b>No
 * se tocan</b> —cancelar o mover una actividad es una decision de alguien—: se calculan al leer, igual
 * que {@code fueraDeDisponibilidad}, y viajan como pares turno–clase de la misma persona. El id va solo
 * del lado que es de esta sede.
 */
@Component
public class OcupacionEntreSedes {

	public static final String TURNO = "TURNO";
	public static final String CLASE = "CLASE";

	private final TurnoRepositoryPort turnos;
	private final OcupacionExternaProbe clases;
	private final AgendaDelProfesional agendaDelProfesional;

	public OcupacionEntreSedes(
			TurnoRepositoryPort turnos, OcupacionExternaProbe clases, AgendaDelProfesional agendaDelProfesional) {
		this.turnos = turnos;
		this.clases = clases;
		this.agendaDelProfesional = agendaDelProfesional;
	}

	/** Las dos listas de una vez: comparten las mismas dos lecturas. */
	public record Resultado(List<OcupacionEnOtraSedeView> ocupaciones, List<ConflictoDeAgendaView> conflictos) {

		static final Resultado VACIO = new Resultado(List.of(), List.of());
	}

	/**
	 * @param columnas memberships de profesional de ESTA sede (las columnas de la agenda)
	 */
	public Resultado de(
			long organizationId, long consultorioId, Collection<Long> columnas, Instant desde, Instant hasta) {

		Map<Long, Long> columnaDe = new HashMap<>();
		for (Long columna : columnas) {
			for (Long misma : agendaDelProfesional.deLaMismaPersona(organizationId, columna)) {
				columnaDe.putIfAbsent(misma, columna);
			}
		}
		if (columnaDe.isEmpty()) {
			return Resultado.VACIO;
		}
		List<Turno> turnosDeLasPersonas =
				turnos.findVivosDeProfesionalesQueCruzan(organizationId, columnaDe.keySet(), desde, hasta);
		List<OcupacionExterna> clasesDeLasPersonas =
				clases.ocupacionDeProfesionales(organizationId, columnaDe.keySet(), desde, hasta);

		List<OcupacionEnOtraSedeView> ocupaciones = new ArrayList<>();
		for (Turno turno : turnosDeLasPersonas) {
			if (turno.getConsultorioId() != consultorioId) {
				ocupaciones.add(new OcupacionEnOtraSedeView(columnaDe.get(turno.getProfesionalMembershipId()),
						TURNO, turno.getConsultorioId(), turno.getInicio(), turno.getFin()));
			}
		}
		for (OcupacionExterna clase : clasesDeLasPersonas) {
			if (clase.consultorioId() != consultorioId) {
				ocupaciones.add(new OcupacionEnOtraSedeView(columnaDe.get(clase.profesionalMembershipId()),
						CLASE, clase.consultorioId(), clase.inicio(), clase.fin()));
			}
		}
		ocupaciones.sort(Comparator.comparing(OcupacionEnOtraSedeView::inicio));

		List<ConflictoDeAgendaView> conflictos = new ArrayList<>();
		for (Turno turno : turnosDeLasPersonas) {
			Long columna = columnaDe.get(turno.getProfesionalMembershipId());
			for (OcupacionExterna clase : clasesDeLasPersonas) {
				boolean tocaEstaSede = turno.getConsultorioId() == consultorioId || clase.consultorioId() == consultorioId;
				if (tocaEstaSede && Objects.equals(columna, columnaDe.get(clase.profesionalMembershipId()))
						&& clase.seCruzaCon(turno.getInicio(), turno.getFin())) {
					conflictos.add(new ConflictoDeAgendaView(columna,
							turno.getConsultorioId() == consultorioId ? turno.getId() : null,
							turno.getConsultorioId(),
							clase.consultorioId() == consultorioId ? clase.eventoId() : null,
							clase.consultorioId(),
							max(turno.getInicio(), clase.inicio()),
							min(turno.getFin(), clase.fin())));
				}
			}
		}
		conflictos.sort(Comparator.comparing(ConflictoDeAgendaView::inicio));
		return new Resultado(List.copyOf(ocupaciones), List.copyOf(conflictos));
	}

	private static Instant max(Instant a, Instant b) {
		return a.isAfter(b) ? a : b;
	}

	private static Instant min(Instant a, Instant b) {
		return a.isBefore(b) ? a : b;
	}
}
