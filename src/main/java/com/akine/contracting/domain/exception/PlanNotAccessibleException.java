package com.akine.contracting.domain.exception;

/**
 * El plan no existe, es de otra organizacion, o es de otro financiador que el de la ruta. Los
 * tres responden 404 con el mismo texto, por el mismo motivo anti-enumeracion de siempre.
 */
public class PlanNotAccessibleException extends RuntimeException {

	private final long planId;

	public PlanNotAccessibleException(long planId) {
		super("Plan de cobertura no accesible: " + planId);
		this.planId = planId;
	}

	public long getPlanId() {
		return planId;
	}
}
