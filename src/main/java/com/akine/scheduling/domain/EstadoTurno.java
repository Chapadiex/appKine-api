package com.akine.scheduling.domain;

/**
 * Estados de la RESERVA, y solo de la reserva.
 *
 * <p>DP-05 los separa de la recepcion y de la sesion en tres maquinas independientes: Turno
 * representa exclusivamente que un lugar quedo tomado. <b>Ninguna transicion de aca prueba que la
 * atencion haya ocurrido</b>, y por eso no existe un estado {@code ATENDIDO}: eso lo dice la
 * Sesion, en M14.
 *
 * <p>AKINE-05.02 creo los dos primeros y 05.03 agrega los dos ultimos. <b>Los valores no se
 * declaran de antemano</b>: un enum con estados que ninguna transicion alcanza es una promesa que
 * el codigo no cumple. No existe un estado de llegada —el check-in es de la {@link Recepcion}
 * desde E-4 (DP-16)— y tampoco existe {@code REPROGRAMADO}: reprogramar mueve el turno y lo
 * devuelve a {@code RESERVADO}, no lo deja en un estado terminal.
 *
 * <h2>La maquina completa</h2>
 *
 * <pre>
 *   RESERVADO  --confirmar-->     CONFIRMADO
 *   RESERVADO  --cancelar-->      CANCELADO      (futuro, motivo obligatorio, libera el lugar)
 *   CONFIRMADO --cancelar-->      CANCELADO
 *   RESERVADO  --ausencia-->      AUSENTE        (pasado, NO libera el lugar)
 *   CONFIRMADO --ausencia-->      AUSENTE
 *   RESERVADO  --reprogramar-->   RESERVADO      (futuro, con intervalo nuevo)
 *   CONFIRMADO --reprogramar-->   RESERVADO      (la confirmacion era para OTRO horario)
 * </pre>
 *
 * <p>{@code CANCELADO} y {@code AUSENTE} son terminales: un turno que ya termino su ciclo no
 * vuelve. DP-04 lo dice para los turnos pasados —"permaneceran inalterables"— y la cancelacion
 * hereda la misma regla, porque su lugar ya se libero y puede estar tomado por otro.
 */
public enum EstadoTurno {

	/** Lugar tomado. Es el estado con el que nace todo turno, y al que vuelve si se reprograma. */
	RESERVADO,

	/**
	 * Reserva confirmada por quien la tomo o por el centro.
	 *
	 * <p>Es un estado de la reserva y <b>no</b> del cobro ni de la llegada del paciente: DP-06
	 * deja el prepago como politica configurable y nunca como condicion del dominio clinico.
	 */
	CONFIRMADO,

	/**
	 * Reserva deshecha con motivo declarado. <b>Libera el lugar</b> y conserva la fila.
	 *
	 * <p>RN-M12-002: cancelar no elimina fisicamente. La fila queda con {@code deleted_at}, que es
	 * lo que las consultas de solapamiento miran para dejar de contarla.
	 */
	CANCELADO,

	/**
	 * El paciente no vino. <b>No libera el lugar</b>: la hora se consumio igual.
	 *
	 * <p>DP-04: la ausencia se registra y <b>nunca</b> elimina nada, ni el turno ni —cuando exista
	 * el modelo de serie— los turnos siguientes.
	 */
	AUSENTE;

	// EN_ESPERA vivio aca desde 05.04 hasta E-4. DP-16 lo saco: la llegada, la espera y el llamado
	// son de la Recepcion (EstadoRecepcion), que tiene maquina propia como pedia DP-05. V78 migro
	// los turnos que estaban en espera al estado del que venian y su llegada a `recepcion`. El
	// contrato lo sigue declarando, deprecado, una version mas: ver TurnoResponse.

	/** {@code true} si desde este estado todavia se puede reprogramar o marcar ausencia. */
	public boolean admiteTransicion() {
		return this == RESERVADO || this == CONFIRMADO;
	}

	/**
	 * {@code true} si desde este estado todavia se puede cancelar.
	 *
	 * <p>Hoy coincide con {@link #admiteTransicion()}. Se conserva aparte porque las dos preguntas
	 * son distintas: una recepcion abierta impide reprogramar y marcar ausencia pero <b>no</b>
	 * cancelar —el paciente llego y el profesional no lo pudo atender—, y esa diferencia ya no la
	 * decide el estado del turno sino {@code CicloDeTurnoService} mirando la recepcion.
	 */
	public boolean admiteCancelacion() {
		return this == RESERVADO || this == CONFIRMADO;
	}

	/**
	 * {@code true} si el turno admite que se registre la llegada del paciente en la recepcion.
	 *
	 * <p>Un turno cancelado no admite llegada —no hay a que llegar— y uno ya marcado ausente
	 * tampoco: la ausencia afirma que <b>no</b> vino. Si el ausente fue un error, primero se
	 * corrige la ausencia.
	 */
	public boolean admiteLlegada() {
		return this == RESERVADO || this == CONFIRMADO;
	}

	/** {@code true} si el turno cerro su ciclo y ya no admite ningun cambio. */
	public boolean esTerminal() {
		return this == CANCELADO || this == AUSENTE;
	}
}
