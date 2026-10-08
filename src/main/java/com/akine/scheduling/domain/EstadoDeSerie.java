package com.akine.scheduling.domain;

import java.time.Instant;
import java.util.Collection;

/**
 * Estado DERIVADO de una serie de turnos, para la bandeja (AKINE E-8, E-8b).
 *
 * <p>La serie no tiene estado persistido y no lo va a tener (E-3, ADR-0011): "la serie esta
 * cancelada" es un hecho de sus turnos, no una columna que pueda contradecirlos. Este enum es la
 * lectura de esos turnos en un instante, calculada al leer y nunca guardada.
 *
 * <p>La regla (DP-20), sobre TODOS los turnos de la serie —tambien los cancelados, que desde 05.03
 * llevan {@code deleted_at} para liberar el lugar—:
 * <ol>
 *   <li>{@link #VIGENTE} si le queda un turno pendiente: RESERVADO o CONFIRMADO, vivo y con
 *       {@code inicio > ahora}. Manda sobre las otras dos.</li>
 *   <li>{@link #CANCELADA} si no le queda ninguno y <b>su ultimo turno esta CANCELADO</b>: ningun
 *       turno no cancelado empieza en el mismo instante o despues que el ultimo cancelado. La
 *       serie no llego a su fin: se la corto, entera o desde la mitad.</li>
 *   <li>{@link #FINALIZADA} en cualquier otro caso: su ultimo turno paso (atendido o no) o se
 *       marco ausente. Se agoto por fecha o por cantidad, aunque haya cancelados en el medio.</li>
 * </ol>
 *
 * <p>El filtro de la bandeja ({@code TurnoSerieRepository.WHERE_BANDEJA}) es esta misma regla
 * escrita en JPQL: si divergieran, una serie filtrada por un estado se mostraria con otro.
 */
public enum EstadoDeSerie {

	/** Le queda al menos un turno pendiente: RESERVADO o CONFIRMADO, vivo y que todavia no empezo. */
	VIGENTE,

	/** No le queda ningun turno pendiente y su ultimo turno se cancelo: la serie se corto (DP-20). */
	CANCELADA,

	/** No le queda ningun turno pendiente y su ultimo turno no se cancelo: la serie se agoto. */
	FINALIZADA;

	/** Un turno vivo, RESERVADO o CONFIRMADO, que todavia no empezo. */
	public static boolean esPendiente(Turno turno, Instant ahora) {
		return turno.estaVivo() && turno.getEstado().admiteTransicion() && turno.getInicio().isAfter(ahora);
	}

	/** El estado de una serie con esos turnos —todos, cancelados incluidos— en el instante {@code ahora}. */
	public static EstadoDeSerie de(Collection<Turno> turnosDeLaSerie, Instant ahora) {
		Instant ultimoCancelado = null;
		Instant ultimoNoCancelado = null;
		for (Turno turno : turnosDeLaSerie) {
			if (esPendiente(turno, ahora)) {
				return VIGENTE;
			}
			if (turno.getEstado() == EstadoTurno.CANCELADO) {
				ultimoCancelado = masTarde(ultimoCancelado, turno.getInicio());
			} else {
				ultimoNoCancelado = masTarde(ultimoNoCancelado, turno.getInicio());
			}
		}
		if (ultimoCancelado != null
				&& (ultimoNoCancelado == null || ultimoCancelado.isAfter(ultimoNoCancelado))) {
			return CANCELADA;
		}
		return FINALIZADA;
	}

	private static Instant masTarde(Instant actual, Instant candidato) {
		return actual == null || candidato.isAfter(actual) ? candidato : actual;
	}
}
