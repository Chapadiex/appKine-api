package com.akine.scheduling.domain;

/**
 * Estados de la RESERVA, y solo de la reserva.
 *
 * <p>DP-05 los separa de la recepcion y de la sesion en tres maquinas independientes: Turno
 * representa exclusivamente que un lugar quedo tomado. <b>Ninguna transicion de aca prueba que la
 * atencion haya ocurrido</b>, y por eso no existe un estado {@code ATENDIDO}: eso lo dice la
 * Sesion, en M14.
 *
 * <p>AKINE-05.02 crea los dos primeros. Cancelacion, reprogramacion y ausencia son de 05.03, que
 * el Paquete B de DP-10 dejo afuera, asi que <b>hoy un turno no se puede deshacer desde ninguna
 * pantalla</b>. Los valores no se declaran de antemano: un enum con estados que ninguna
 * transicion alcanza es una promesa que el codigo no cumple.
 */
public enum EstadoTurno {

	/** Lugar tomado. Es el estado con el que nace todo turno. */
	RESERVADO,

	/**
	 * Reserva confirmada por quien la tomo o por el centro.
	 *
	 * <p>Es un estado de la reserva y <b>no</b> del cobro ni de la llegada del paciente: DP-06
	 * deja el prepago como politica configurable y nunca como condicion del dominio clinico.
	 */
	CONFIRMADO
}
