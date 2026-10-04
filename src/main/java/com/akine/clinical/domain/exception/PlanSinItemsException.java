package com.akine.clinical.domain.exception;

/**
 * El plan no tiene ningun item en su version vigente y por eso no se puede activar (409).
 *
 * <p>Un tratamiento sin ningun item es un tratamiento sin nada que hacer: activarlo dejaria vigente
 * un plan vacio y finalizaria, de paso, el que el caso tenia en curso. Se rechaza antes de tocar
 * nada. <b>No</b> cubre el reintento idempotente —activar lo ya activo responde 200—, que se
 * resuelve antes de mirar los items.
 *
 * <p><b>409 y no 400.</b> El cuerpo del pedido esta bien formado; lo que falta es contenido en el
 * plan, y la accion que corresponde es cargarle items y volver a activarlo.
 */
public class PlanSinItemsException extends RuntimeException {

	private final Long planId;

	public PlanSinItemsException(Long planId) {
		super("El plan de tratamiento " + planId + " no tiene items y no se puede activar");
		this.planId = planId;
	}

	public Long getPlanId() {
		return planId;
	}
}
