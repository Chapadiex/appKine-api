package com.akine.clinical.domain.exception;

import com.akine.clinical.domain.EstadoPlan;

/**
 * La transicion pedida no sale del estado en que esta el plan (409).
 *
 * <p>Cubre las transiciones que no existen en la maquina de estados de RF-M11-006: activar un plan
 * finalizado, reanudar uno que no esta suspendido, suspender un borrador. <b>No</b> cubre los
 * reintentos idempotentes —suspender lo suspendido, activar lo activo—, que se responden 200 con el
 * plan tal como quedo: son el mismo pedido, no un conflicto.
 *
 * <p><b>409 y no 400.</b> El cuerpo del pedido esta bien formado; lo que no encaja es el estado, y
 * la accion que corresponde es releer el plan —puede haber cambiado desde que se dibujo la
 * pantalla— y no corregir un campo. Lleva los dos estados para que el cliente muestre cual era.
 */
public class TransicionDePlanInvalidaException extends RuntimeException {

	private final Long planId;
	private final String estadoActual;
	private final String transicion;

	public TransicionDePlanInvalidaException(
			Long planId, EstadoPlan estadoActual, String transicion) {

		super("El plan de tratamiento " + planId + " esta " + estadoActual
				+ " y esa transicion no es " + transicion);
		this.planId = planId;
		this.estadoActual = estadoActual.name();
		this.transicion = transicion;
	}

	public Long getPlanId() {
		return planId;
	}

	public String getEstadoActual() {
		return estadoActual;
	}

	public String getTransicion() {
		return transicion;
	}
}
