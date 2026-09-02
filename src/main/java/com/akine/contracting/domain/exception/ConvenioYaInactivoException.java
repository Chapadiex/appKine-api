package com.akine.contracting.domain.exception;

/** Operacion sobre un convenio ya dado de baja. {@code operacion} distingue los dos 409. */
public class ConvenioYaInactivoException extends RuntimeException {

	private final long convenioId;

	private final String operacion;

	public ConvenioYaInactivoException(long convenioId, String operacion) {
		super("Convenio ya inactivo: " + convenioId);
		this.convenioId = convenioId;
		this.operacion = operacion;
	}

	public long getConvenioId() {
		return convenioId;
	}

	public String getOperacion() {
		return operacion;
	}
}
