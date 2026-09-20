package com.akine.billing.domain.exception;

/** El egreso ya estaba anulado. <b>409</b>. Anular no borra: la fila sigue ahi con su motivo. */
public class EgresoYaAnuladoException extends RuntimeException {

	private final Long egresoId;

	public EgresoYaAnuladoException(Long egresoId) {
		super("El egreso " + egresoId + " ya estaba anulado");
		this.egresoId = egresoId;
	}

	public Long getEgresoId() {
		return egresoId;
	}
}
