package com.akine.person.application;

import com.akine.person.domain.port.PersonRepositoryPorts.AutorizacionPersonaLockRepositoryPort;

/**
 * Toma el lock que serializa las aprobaciones de autorizaciones de un paciente (M17).
 *
 * <p><b>La fila tiene que existir antes.</b> La crea {@link AutorizacionLockIniciador} en una
 * transaccion aparte, y no aca: crearla dentro de la transaccion que la bloquea produce un
 * DEADLOCK entre las primeras N escrituras concurrentes, no una violacion de unique que una pierda
 * limpiamente. El detalle esta en el javadoc de esa clase.
 *
 * <p><b>Y la transaccion que lo toma va en {@code READ_COMMITTED}.</b> Con {@code REPEATABLE READ}
 * el lock no alcanza: InnoDB fija la foto en la primera lectura consistente, que ocurre antes del
 * lock, asi que la comprobacion de solapamiento leeria un estado anterior a la autorizacion que la
 * otra transaccion acaba de aprobar y las dos pasarian. Es la leccion que 05.02 pago con
 * {@code agenda_sede} y que 03.04 volvio a aplicar.
 *
 * <p><b>El lock se toma ANTES de leer</b> el conjunto que se valida. Leer primero y bloquear
 * despues es una escalada S→X entre dos transacciones simetricas, o sea un deadlock.
 */
final class BloqueoDeAutorizaciones {

	private BloqueoDeAutorizaciones() {
		// Utilidad de concurrencia.
	}

	static void tomar(
			AutorizacionPersonaLockRepositoryPort candados, long organizationId, long personaId) {

		candados.lockByScope(organizationId, personaId)
				.orElseThrow(() -> new IllegalStateException(
						"El candado de autorizaciones de la persona " + personaId + " no existe al "
								+ "tomar el lock. AutorizacionLockIniciador#asegurar tiene que "
								+ "correr antes, y en su propia transaccion."));
	}
}
