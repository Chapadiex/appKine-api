package com.akine.contracting.domain.exception;

/**
 * El financiador no existe o es de otra organizacion. <b>Los dos casos son el mismo</b>: el
 * advice responde 404 sin distinguirlos, porque un 403 confirmaria que ese id existe y bastaria
 * recorrer numeros para averiguar con que obras sociales trabaja cada centro del SaaS.
 */
public class FinanciadorNotAccessibleException extends RuntimeException {

	private final long financiadorId;

	public FinanciadorNotAccessibleException(long financiadorId) {
		super("Financiador no accesible: " + financiadorId);
		this.financiadorId = financiadorId;
	}

	public long getFinanciadorId() {
		return financiadorId;
	}
}
