package com.akine.encounter.domain;

/**
 * Estados de la ATENCION, y solo de la atencion.
 *
 * <p>DP-05 los separa de los del Turno y de los de la Recepcion. Que un turno este confirmado no
 * dice nada de si hubo atencion, y que una sesion este abierta no dice nada de si se cobro
 * —DP-06 desacopla el cierre clinico del pago—.
 *
 * <p>Solo existen los estados que alguna transicion alcanza. La enmienda de una sesion cerrada es
 * 06.06, que el Paquete B dejo afuera, y por eso no hay un estado para ella: declararlo seria una
 * promesa que el codigo no cumple.
 */
public enum EstadoSesion {

	/** Atencion abierta. Admite autosave del borrador. Es el estado con el que nace toda sesion. */
	BORRADOR,

	/**
	 * Atencion cerrada, con su correlativo asignado.
	 *
	 * <p>No estaba en AKINE-06.05 cuando se escribio el cierre, y eso era un defecto: la sesion
	 * cerrada seguia reportando {@code BORRADOR} y cualquier cliente que leyera el estado la
	 * creia abierta. Lo destapo el QA manual contra el stack real. El frontend habia tenido que
	 * derivarlo de {@code numeroSesion}, que es sintoma de que el campo mentia.
	 */
	CERRADA
}
