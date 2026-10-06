package com.akine.billing.domain.exception;

/**
 * Se intenta anular un cobro que ya reintegro parte de su saldo a favor. <b>409</b>.
 *
 * <p>La anulacion revierte los ingresos de caja del cobro por entero. Si una parte ya se le
 * devolvio al paciente, esa plata saldria del cajon dos veces.
 */
public class CobroConReintegrosException extends RuntimeException {

	private final Long cobroId;

	public CobroConReintegrosException(Long cobroId) {
		super("El cobro " + cobroId + " tiene reintegros: anularlo devolveria dos veces la misma plata");
		this.cobroId = cobroId;
	}

	public Long getCobroId() {
		return cobroId;
	}
}
