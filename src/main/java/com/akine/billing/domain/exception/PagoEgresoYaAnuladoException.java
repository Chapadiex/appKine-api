package com.akine.billing.domain.exception;

/** El pago ya estaba anulado. <b>409</b>. Revertir dos veces sacaria plata del cajon dos veces. */
public class PagoEgresoYaAnuladoException extends RuntimeException {

	private final Long pagoId;

	public PagoEgresoYaAnuladoException(Long pagoId) {
		super("El pago de egreso " + pagoId + " ya estaba anulado");
		this.pagoId = pagoId;
	}

	public Long getPagoId() {
		return pagoId;
	}
}
