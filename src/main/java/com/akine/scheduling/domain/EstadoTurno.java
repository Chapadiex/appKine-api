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
 * el codigo no cumple. Sigue sin existir un estado de llegada —eso es el check-in de 05.04, que
 * DP-10 dejo afuera— y tampoco existe {@code REPROGRAMADO}: reprogramar mueve el turno y lo
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
	AUSENTE,

	/**
	 * El paciente llego al centro y espera ser atendido (M13, AKINE-05.04).
	 *
	 * <p><b>Es un estado de la RESERVA y no de la atencion.</b> DP-05 separa Turno, Recepcion y
	 * Sesion: la recepcionista marca la llegada sin abrir ninguna atencion, y el profesional abre
	 * la atencion sin depender de que alguien la haya marcado. Que un turno no pase por aca no
	 * impide atenderlo — impide saber a que hora llego el paciente, que es otra cosa.
	 *
	 * <p>Tampoco es "atendido": la prestacion la registra la Sesion, y su cierre es el que decide
	 * si hubo asistencia. Un paciente puede estar EN_ESPERA y irse antes de que lo llamen.
	 */
	EN_ESPERA;

	/** {@code true} si desde este estado todavia se puede reprogramar o marcar ausencia. */
	public boolean admiteTransicion() {
		return this == RESERVADO || this == CONFIRMADO;
	}

	/**
	 * {@code true} si desde este estado todavia se puede cancelar.
	 *
	 * <p>Es mas amplio que {@link #admiteTransicion()} por un caso concreto: <b>el paciente llego y
	 * el profesional no lo pudo atender</b>. Ese turno hay que poder cancelarlo, y obligar a
	 * deshacer antes el check-in seria peor que permitirlo — borraria la evidencia de que la
	 * persona vino, que es justamente lo que la recepcion existe para registrar.
	 *
	 * <p>Reprogramar y marcar ausencia siguen fuera desde {@code EN_ESPERA}: mover un turno cuya
	 * hora ya llego no tiene sentido, y afirmar que no vino alguien que esta en la sala es
	 * directamente falso.
	 */
	public boolean admiteCancelacion() {
		return this == RESERVADO || this == CONFIRMADO || this == EN_ESPERA;
	}

	/**
	 * {@code true} si desde este estado se puede registrar la llegada del paciente.
	 *
	 * <p>Un turno cancelado no admite llegada —no hay a que llegar— y uno ya marcado ausente
	 * tampoco: la ausencia afirma que <b>no</b> vino, y aceptar el check-in encima dejaria la fila
	 * diciendo las dos cosas. Si el ausente fue un error, primero se corrige la ausencia.
	 */
	public boolean admiteLlegada() {
		return this == RESERVADO || this == CONFIRMADO;
	}

	/**
	 * {@code true} si el turno cerro su ciclo y ya no admite ningun cambio.
	 *
	 * <p>{@code EN_ESPERA} <b>no</b> es terminal: el paciente que llego todavia puede irse, y de
	 * ahi se sale deshaciendo el check-in o cancelando.
	 */
	public boolean esTerminal() {
		return this == CANCELADO || this == AUSENTE;
	}
}
