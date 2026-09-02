package com.akine.contracting.domain.exception;

/** Operacion sobre un arancel ya dado de baja. {@code operacion} distingue los dos 409. */
public class ArancelYaInactivoException extends RuntimeException {

	private final long arancelId;

	private final String operacion;

	public ArancelYaInactivoException(long arancelId, String operacion) {
		super("Arancel ya inactivo: " + arancelId);
		this.arancelId = arancelId;
		this.operacion = operacion;
	}

	public long getArancelId() {
		return arancelId;
	}

	public String getOperacion() {
		return operacion;
	}
}
