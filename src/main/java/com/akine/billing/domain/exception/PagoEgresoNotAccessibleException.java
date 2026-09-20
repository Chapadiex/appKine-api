package com.akine.billing.domain.exception;

/** El pago no existe, no es de ese egreso, o es de otro tenant. <b>404</b>, nunca 403. */
public class PagoEgresoNotAccessibleException extends RuntimeException {

	private final Long pagoId;

	public PagoEgresoNotAccessibleException(Long pagoId) {
		super("El pago de egreso " + pagoId + " no existe en este contexto");
		this.pagoId = pagoId;
	}

	public Long getPagoId() {
		return pagoId;
	}
}
