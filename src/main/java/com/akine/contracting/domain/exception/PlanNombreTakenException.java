package com.akine.contracting.domain.exception;

/** Ya hay un plan VIGENTE con ese nombre en ese financiador. */
public class PlanNombreTakenException extends RuntimeException {

	public PlanNombreTakenException() {
		super("Nombre de plan en uso");
	}
}
