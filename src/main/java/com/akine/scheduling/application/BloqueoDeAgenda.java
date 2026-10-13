package com.akine.scheduling.application;

import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.scheduling.domain.AgendaSede;
import com.akine.scheduling.domain.exception.SedeDadaDeBajaException;
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

	/**
	 * Toma el lock y, <b>bajo el</b>, revalida que la sede siga activa. Para las escrituras que
	 * OCUPAN la agenda: reservar, reprogramar y dar de alta o mover una serie.
	 *
	 * <h2>La carrera que cierra (decision pendiente 8b)</h2>
	 *
	 * <p>La baja de la sede toma el lock del tenant y pregunta a {@code SedeConTurnosPendientes} si
	 * quedan turnos; la reserva toma el de {@code agenda_sede}. Son locks distintos, y la sede se
	 * validaba fuera de los dos: la reserva leia la sede activa, la sonda de la baja contaba cero
	 * turnos, y las dos commiteaban. Quedaba un turno vivo en una sede dada de baja.
	 *
	 * <p>Ahora las dos se encuentran en la fila de {@code consultorio}: la baja la toma en
	 * exclusivo antes de la sonda, y esto la relee en compartido. La segunda en llegar espera a la
	 * primera y ve lo que dejo: o la sonda de la baja ve el turno, o esto ve la sede de baja.
	 *
	 * <p>Orden de locks: {@code agenda_sede} y despues {@code consultorio}. La baja toma
	 * {@code subscription} y {@code consultorio}, y nunca {@code agenda_sede}: no hay ciclo.
	 *
	 * <p>Cancelar, marcar ausente o confirmar usan {@link #tomar} y no esto: liberar la agenda de
	 * una sede de baja es justamente lo que hay que poder hacer.
	 *
	 * @throws SedeDadaDeBajaException la sede ya no esta activa (409 {@code consultorio-inactive})
	 */
	static AgendaSede tomarParaOcupar(AgendaSedeRepositoryPort agendas,
			ConsultorioDirectory consultorios, long organizationId, long consultorioId) {

		AgendaSede agenda = tomar(agendas, organizationId, consultorioId);
		if (consultorios.inactivaBajoLockCompartido(organizationId, consultorioId)) {
			throw new SedeDadaDeBajaException(consultorioId);
		}
		return agenda;
	}
}
