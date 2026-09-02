package com.akine.contracting.domain.exception;

/** Operacion sobre un plan ya dado de baja. {@code operacion} distingue los dos 409. */
public class PlanYaInactivoException extends RuntimeException {

	private final long planId;

	private final String operacion;

	public PlanYaInactivoException(long planId, String operacion) {
		super("Plan ya inactivo: " + planId);
		this.planId = planId;
		this.operacion = operacion;
	}

	public long getPlanId() {
		return planId;
	}

	public String getOperacion() {
		return operacion;
	}
}
