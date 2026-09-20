package com.akine.billing.domain.exception;

/**
 * Se confirma un egreso sin tipo ni numero de comprobante. <b>400</b>.
 *
 * <p>400 y no 409: el estado del servidor esta perfecto y lo que falta es un campo del cuerpo.
 * Mismo criterio que {@code caja-diferencia-sin-motivo} en 07.03.
 *
 * <p>Un egreso confirmado sin respaldo documental es plata que salio sin papel, que es exactamente
 * lo que una auditoria busca. En {@code BORRADOR} el comprobante es opcional a proposito: la
 * liquidacion se arma antes de tener la factura en la mano.
 */
public class EgresoSinComprobanteException extends RuntimeException {

	private final Long egresoId;

	public EgresoSinComprobanteException(Long egresoId) {
		super("El egreso " + egresoId + " no se puede confirmar sin comprobante");
		this.egresoId = egresoId;
	}

	public Long getEgresoId() {
		return egresoId;
	}
}
