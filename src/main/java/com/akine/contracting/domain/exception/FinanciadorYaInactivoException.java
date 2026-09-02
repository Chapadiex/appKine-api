package com.akine.contracting.domain.exception;

/**
 * Operacion sobre un financiador que ya estaba dado de baja.
 *
 * <p>{@code operacion} distingue los dos 409 que salen de aca: dar de baja algo ya dado de baja
 * es {@code financiador-already-inactive}, y editarlo es {@code financiador-inactivo}. Reabrir la
 * ficha de algo dado de baja para renombrarlo reescribiria el historico que RN-M15-003 protege.
 */
public class FinanciadorYaInactivoException extends RuntimeException {

	private final long financiadorId;

	private final String operacion;

	public FinanciadorYaInactivoException(long financiadorId, String operacion) {
		super("Financiador ya inactivo: " + financiadorId);
		this.financiadorId = financiadorId;
		this.operacion = operacion;
	}

	public long getFinanciadorId() {
		return financiadorId;
	}

	public String getOperacion() {
		return operacion;
	}
}
