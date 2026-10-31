package com.akine.scheduling.domain.exception;

/**
 * El plan no tiene sesiones por programar para esa prestacion (TURNO - Tecnico §70).
 *
 * <p>Lleva los dos numeros porque el mensaje correcto los nombra: "el plan tiene unicamente 3
 * sesiones pendientes" le dice al operador cuantas puede pedir; un "no se puede" no le dice nada.
 */
public class PlanSinSesionesException extends RuntimeException {

	private final long planId;
	private final int pendientes;
	private final int pedidas;

	public PlanSinSesionesException(long planId, int pendientes, int pedidas) {
		super(pendientes == 0
				? "El plan no tiene sesiones pendientes para esta prestacion."
				: "El plan tiene unicamente " + pendientes + " sesiones pendientes y se pidieron " + pedidas + ".");
		this.planId = planId;
		this.pendientes = pendientes;
		this.pedidas = pedidas;
	}

	public long getPlanId() {
		return planId;
	}

	public int getPendientes() {
		return pendientes;
	}

	public int getPedidas() {
		return pedidas;
	}
}
