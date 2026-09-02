package com.akine.person.application;

import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPersonaLockRepositoryPort;

/**
 * Toma el lock que serializa las escrituras de coberturas de un paciente.
 *
 * <p><b>La fila tiene que existir antes.</b> La crea {@link CoberturaLockIniciador} en una
 * transaccion aparte, y no aca: crearla dentro de la transaccion que la bloquea produce un
 * DEADLOCK entre las primeras N escrituras concurrentes, no una violacion de unique que una pierda
 * limpiamente. El detalle esta en el javadoc de esa clase.
 *
 * <p><b>Y la transaccion que lo toma va en {@code READ_COMMITTED}.</b> Con {@code REPEATABLE READ}
 * el lock no alcanza: InnoDB fija la foto en la primera lectura consistente, que ocurre antes del
 * lock, asi que la comprobacion de solapamiento leeria un estado anterior a la fila que la otra
 * transaccion acaba de insertar y las dos pasarian.
 */
final class BloqueoDeCoberturas {

	private BloqueoDeCoberturas() {
		// Utilidad de concurrencia.
	}

	static void tomar(
			CoberturaPersonaLockRepositoryPort candados, long organizationId, long personaId) {

		candados.lockByScope(organizationId, personaId)
				.orElseThrow(() -> new IllegalStateException(
						"El candado de coberturas de la persona " + personaId + " no existe al "
								+ "tomar el lock. CoberturaLockIniciador#asegurar tiene que correr "
								+ "antes, y en su propia transaccion."));
	}
}
