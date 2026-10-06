package com.akine.billing.domain.exception;

/**
 * El cobro esta anulado y la operacion exige uno vigente. <b>409</b>.
 *
 * <p>Cubre anular dos veces —revertiria la caja dos veces— y usar el saldo a favor de un cobro
 * anulado, que es plata que la anulacion ya devolvio.
 */
public class CobroAnuladoException extends RuntimeException {

	private final Long cobroId;

	public CobroAnuladoException(Long cobroId) {
		super("El cobro " + cobroId + " esta anulado");
		this.cobroId = cobroId;
	}

	public Long getCobroId() {
		return cobroId;
	}
}
