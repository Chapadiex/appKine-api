package com.akine.organization.spi;

import java.time.Instant;

/**
 * Que queda pendiente cuando se desvincula a un colaborador (RN-M05-004, AKINE-02.03).
 *
 * <h2>Por que esto NO bloquea, a diferencia de {@link ConsultorioDeactivationProbe}</h2>
 *
 * <p>Son dos reglas distintas y conviene no confundirlas. RN-M04-002 dice que una sede con
 * operaciones vigentes no se da de baja: ahi la sonda <b>impide</b>. RN-M05-004 dice otra cosa
 * —"los turnos futuros afectados deben quedar <b>visibles para resolucion</b>"— y esa palabra
 * es toda la diferencia: cuando alguien renuncia, renuncio; el sistema no puede negarse a
 * registrarlo porque tenga la agenda llena. Lo que tiene que hacer es <b>no perder</b> esos
 * turnos y ponerlos donde alguien los reasigne.
 *
 * <p>Por eso esta sonda alimenta dos cosas y ninguna es un rechazo:
 *
 * <ol>
 *   <li>El <b>analisis de impacto</b> que la interfaz muestra <b>antes</b> de confirmar. Quien
 *       desvincula tiene que saber que hay catorce turnos la semana que viene mientras todavia
 *       puede elegir el momento, no enterarse despues.</li>
 *   <li>Los <b>detalles del evento de auditoria</b> de la revocacion. Seis meses despues, "por
 *       que estos turnos quedaron sin profesional" se responde con esa fila.</li>
 * </ol>
 *
 * <h2>Implementaciones</h2>
 *
 * <p>Conviven dos, y {@code MembershipService} reporta la <b>primera con contenido</b>, no la
 * suma: {@code scheduling.infrastructure.ProfesionalConTurnosPendientes} (paquete E-1, va
 * primero) cuenta los turnos pendientes, y {@code resource.infrastructure.ResourceDesvinculacionProbe}
 * los bloques y excepciones de disponibilidad vigentes.
 *
 * <p><b>Quien implemente esto: no lo convierta en un bloqueo.</b> Si la agenda decide que hay
 * casos en los que la desvinculacion no puede proceder, eso es una regla nueva que necesita su
 * propio RF y su propio ADR, no un {@code throw} agregado adentro de una sonda que todo el
 * mundo lee como informativa.
 */
public interface ColaboradorDesvinculacionProbe {

	/**
	 * Trabajo pendiente que quedaria sin dueño.
	 *
	 * @param tipo  que son, en plural y en lenguaje del usuario: "turnos", "sesiones abiertas".
	 *              {@code null} cuando no hay nada
	 * @param count cuantos son. Cero cuando no hay nada
	 * @param desde instante del primero, para que la interfaz pueda decir "desde el martes".
	 *              {@code null} cuando no hay nada
	 */
	record Impacto(String tipo, long count, Instant desde) {

		/** La respuesta de un modulo que no tiene nada que reportar. */
		public static Impacto ninguno() {
			return new Impacto(null, 0L, null);
		}

		/** {@code true} si hay algo que mostrarle a quien esta por desvincular. */
		public boolean hayAlgo() {
			return count > 0;
		}
	}

	/**
	 * Que queda pendiente de esa membership a partir de {@code at}.
	 *
	 * <p>Se consulta con la membership y con la cuenta: la agenda puede indexar por cualquiera
	 * de las dos y no corresponde que este puerto decida cual usa.
	 *
	 * @param at momento desde el que se considera "futuro". Lo fija quien llama, y no la sonda,
	 *           para que el analisis previo y la revocacion hablen del mismo instante
	 */
	Impacto pendingWorkOn(long organizationId, long membershipId, long accountId, Instant at);
}
