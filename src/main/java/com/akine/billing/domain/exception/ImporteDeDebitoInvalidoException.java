package com.akine.billing.domain.exception;

import java.math.BigDecimal;

/**
 * El debito no va entre cero y lo presentado por ese item. <b>400</b>.
 *
 * <p>Es un limite del item, no del lote: debitar 50.000 de una prestacion de 15.000 le declararia
 * al financiador un rechazo por plata que nunca se le reclamo por ese concepto, aunque el lote
 * tenga saldo de sobra.
 */
public class ImporteDeDebitoInvalidoException extends RuntimeException {

	private final BigDecimal importe;
	private final BigDecimal importePresentado;

	public ImporteDeDebitoInvalidoException(BigDecimal importe, BigDecimal importePresentado) {
		super("El debito va entre cero y lo presentado: " + importe + " sobre " + importePresentado);
		this.importe = importe;
		this.importePresentado = importePresentado;
	}

	public BigDecimal getImporte() {
		return importe;
	}

	public BigDecimal getImportePresentado() {
		return importePresentado;
	}
}
