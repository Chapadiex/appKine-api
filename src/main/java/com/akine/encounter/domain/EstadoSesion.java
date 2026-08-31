package com.akine.encounter.domain;

/**
 * Estados de la ATENCION, y solo de la atencion.
 *
 * <p>DP-05 los separa de los del Turno y de los de la Recepcion. Que un turno este confirmado no
 * dice nada de si hubo atencion, y que una sesion este abierta no dice nada de si se cobro
 * —DP-06 desacopla el cierre clinico del pago—.
 *
 * <p>Solo existen los estados que alguna transicion alcanza hoy. {@code CERRADA} llega en 06.05 y
 * la enmienda de una cerrada en 06.06, que el Paquete B dejo afuera: declararlos de antemano seria
 * una promesa que el codigo no cumple.
 */
public enum EstadoSesion {

	/** Atencion abierta. Admite autosave del borrador. Es el estado con el que nace toda sesion. */
	BORRADOR
}
