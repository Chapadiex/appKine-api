package com.akine.contracting.domain.exception;

/** Ya hay un financiador VIGENTE con ese codigo en la organizacion. El de uno dado de baja si se reusa. */
public class FinanciadorCodigoTakenException extends RuntimeException {

	private final String codigo;

	public FinanciadorCodigoTakenException(String codigo) {
		super("Codigo de financiador en uso");
		this.codigo = codigo;
	}

	public String getCodigo() {
		return codigo;
	}
}
