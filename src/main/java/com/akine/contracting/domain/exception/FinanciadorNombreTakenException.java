package com.akine.contracting.domain.exception;

/** Ya hay un financiador VIGENTE con ese nombre en la organizacion. */
public class FinanciadorNombreTakenException extends RuntimeException {

	public FinanciadorNombreTakenException() {
		super("Nombre de financiador en uso");
	}
}
