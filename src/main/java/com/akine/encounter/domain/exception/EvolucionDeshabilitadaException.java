package com.akine.encounter.domain.exception;

/**
 * La evolucion del caso esta apagada en este despliegue (RN-M31-010). Responde 404: con el
 * interruptor apagado, la operacion no existe.
 */
public class EvolucionDeshabilitadaException extends RuntimeException {

	public EvolucionDeshabilitadaException() {
		super("La evolucion del caso no esta habilitada en este despliegue");
	}
}
