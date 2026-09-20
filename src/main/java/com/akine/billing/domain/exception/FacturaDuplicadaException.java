package com.akine.billing.domain.exception;

/**
 * Ese numero de factura ya esta en otro lote del mismo financiador. <b>409</b>.
 *
 * <p>Es el caso borde "factura externa duplicada" de la etapa. El comprobante es <b>del centro</b>
 * y se emite fuera de AKINE: el sistema no lo genera ni lo numera, lo registra. Lo unico que puede
 * hacer —y hace, con el unique de V56— es impedir que el mismo numero quede asociado a dos lotes.
 */
public class FacturaDuplicadaException extends RuntimeException {

	private final String facturaNumero;

	public FacturaDuplicadaException(String facturaNumero) {
		super("La factura " + facturaNumero
				+ " ya esta registrada en otra presentacion de este financiador");
		this.facturaNumero = facturaNumero;
	}

	public String getFacturaNumero() {
		return facturaNumero;
	}
}
