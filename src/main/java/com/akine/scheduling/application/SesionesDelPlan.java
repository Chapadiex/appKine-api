package com.akine.scheduling.application;

import com.akine.clinical.spi.ContextoClinicoDeAgenda.ItemAgendable;
import com.akine.clinical.spi.ContextoClinicoDeAgenda.PlanAgendable;
import com.akine.scheduling.domain.EstadoTurno;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.PlanSinSesionesException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import com.akine.scheduling.spi.AtencionProbe;
import com.akine.scheduling.spi.AtencionProbe.AtencionDeTurno;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cuantas sesiones de un plan quedan por programar (TURNO - Tecnico §69 y §70).
 *
 * <p><b>Se calcula al leer y no se guarda</b>, como el avance del plan y la disponibilidad: un
 * contador en una tabla es una segunda copia de la verdad.
 *
 * <pre>
 *   realizadas  sesiones cerradas con el paciente presente (las cuenta clinical, por caso y oferta)
 *   programadas turnos del plan, vivos, sin atencion cerrada
 *   ausencias   turnos del plan marcados ausentes con "consume una sesion"
 *   pendientes  planificadas - realizadas - programadas - ausencias, y nunca menos de cero
 * </pre>
 *
 * <p>Una atencion cerrada saca al turno de "programadas" porque ya esta en "realizadas": sin eso la
 * misma sesion se contaria dos veces, porque cerrar no cambia el estado del turno (DP-05).
 */
@Component
class SesionesDelPlan {

	/** @param numeroDeTurno que sesion de su prestacion es cada turno vivo del plan, desde 1 */
	record ResumenDelPlan(long planId, List<ItemDelPlan> items, Map<Long, Integer> numeroDeTurno) {

		ItemDelPlan item(long ofertaId) {
			return items.stream().filter(item -> item.ofertaId() == ofertaId).findFirst().orElse(null);
		}
	}

	record ItemDelPlan(
			long ofertaId,
			long consultorioId,
			String ofertaNombre,
			int planificadas,
			int realizadas,
			int programadas,
			int ausenciasQueConsumen,
			int pendientes) {
	}

	private final TurnoRepositoryPort turnos;
	private final AtencionProbe atenciones;

	SesionesDelPlan(TurnoRepositoryPort turnos, AtencionProbe atenciones) {
		this.turnos = turnos;
		this.atenciones = atenciones;
	}

	ResumenDelPlan resumir(long organizationId, PlanAgendable plan) {
		List<Turno> delPlan = turnos.findDeLosPlanes(organizationId, List.of(plan.planId()));
		Map<Long, AtencionDeTurno> atencionDe = atenciones.atencionesDe(
				organizationId, delPlan.stream().map(Turno::getId).toList());

		List<ItemDelPlan> items = new ArrayList<>();
		Map<Long, Integer> numeroDeTurno = new HashMap<>();
		for (ItemAgendable item : plan.items()) {
			List<Turno> deLaOferta = delPlan.stream()
					.filter(turno -> turno.getOfertaId() == item.ofertaId())
					.toList();

			int programadas = 0;
			int ausencias = 0;
			int conAtencionCerrada = 0;
			for (Turno turno : deLaOferta) {
				AtencionDeTurno atencion = atencionDe.get(turno.getId());
				if (atencion != null && atencion.cerrada()) {
					conAtencionCerrada++;
				} else if (turno.getEstado() == EstadoTurno.AUSENTE) {
					ausencias += Boolean.TRUE.equals(turno.getAusenciaConsumeSesion()) ? 1 : 0;
				} else if (turno.estaVivo()) {
					programadas++;
				}
			}

			// La numeracion arranca despues de lo que se atendio sin pasar por un turno de este plan:
			// atenciones sin turno, o anteriores a que el turno supiera de su plan.
			int numero = Math.max(0, item.realizadas() - conAtencionCerrada);
			for (Turno turno : deLaOferta) {
				boolean cuenta = turno.estaVivo() && (turno.getEstado() != EstadoTurno.AUSENTE
						|| Boolean.TRUE.equals(turno.getAusenciaConsumeSesion()));
				if (cuenta) {
					numeroDeTurno.put(turno.getId(), ++numero);
				}
			}

			items.add(new ItemDelPlan(item.ofertaId(), item.consultorioId(), item.ofertaNombre(),
					item.planificadas(), item.realizadas(), programadas, ausencias,
					Math.max(0, item.planificadas() - item.realizadas() - programadas - ausencias)));
		}
		return new ResumenDelPlan(plan.planId(), items, numeroDeTurno);
	}

	/**
	 * Exige que queden sesiones. Corre <b>bajo el lock de la sede</b>, despues de leer los turnos, para
	 * que dos programaciones simultaneas no gasten la misma sesion.
	 *
	 * @throws PlanSinSesionesException el plan no tiene las que se piden (409)
	 */
	void exigirDisponibles(long organizationId, PlanAgendable plan, long ofertaId, int cantidad) {
		ItemDelPlan item = resumir(organizationId, plan).item(ofertaId);
		int pendientes = item == null ? 0 : item.pendientes();
		if (pendientes < cantidad) {
			throw new PlanSinSesionesException(plan.planId(), pendientes, cantidad);
		}
	}
}
