package com.akine.contracting.application;

import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioLockRepositoryPort;

/**
 * Toma el lock que serializa las escrituras de convenios y aranceles de una sede.
 *
 * <p><b>La fila tiene que existir antes.</b> La crea {@link ConvenioLockIniciador} en una
 * transaccion aparte, y no aca: crearla dentro de la transaccion que la bloquea produce un DEADLOCK
 * entre las primeras N escrituras concurrentes de una sede, no una violacion de unique que una
 * pierda limpiamente. El detalle esta en el javadoc de esa clase.
 *
 * <p><b>Y se toma ANTES de leer nada del conjunto que se valida.</b> Leer primero y bloquear
 * despues convierte el lock compartido de la lectura en uno exclusivo, y dos transacciones
 * simetricas haciendo esa escalada S a X al mismo tiempo se deadlockean. Es la leccion que 02.04
 * dejo escrita para {@code consultorio_calendario} y que 05.02 repitio para {@code agenda_sede}.
 */
final class BloqueoDeConvenios {

	private BloqueoDeConvenios() {
		// Utilidad de concurrencia.
	}

	static void tomar(
			ConvenioLockRepositoryPort locks, long organizationId, long consultorioId) {

		locks.lockByScope(organizationId, consultorioId)
				.orElseThrow(() -> new IllegalStateException(
						"La fila-lock de convenios de la sede " + consultorioId + " no existe al "
								+ "tomar el lock. ConvenioLockIniciador#asegurar tiene que correr "
								+ "antes, y en su propia transaccion."));
	}
}
