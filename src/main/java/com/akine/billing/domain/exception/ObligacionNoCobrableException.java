package com.akine.billing.domain.exception;

/**
 * Se intenta cobrar contra una deuda que no lo admite. <b>409</b>.
 *
 * <p>Los casos: esta anulada, ya esta pagada, o es de otra persona. El ultimo es el que importa
 * vigilar: sin ese control, un cobro podria saldar la deuda de otro paciente y las dos cuentas
 * corrientes quedarian mal sin que nada falle.
 */
public class ObligacionNoCobrableException extends RuntimeException {

	private final long obligacionId;
	private final String motivo;

	public ObligacionNoCobrableException(long obligacionId, String motivo) {
		super("La obligacion " + obligacionId + " no admite cobro: " + motivo);
		this.obligacionId = obligacionId;
		this.motivo = motivo;
	}

	public long getObligacionId() {
		return obligacionId;
	}

	public String getMotivo() {
		return motivo;
	}
}
