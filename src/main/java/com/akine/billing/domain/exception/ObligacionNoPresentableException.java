package com.akine.billing.domain.exception;

/**
 * Esa deuda no se le puede reclamar a este financiador, en este lote. <b>409</b>.
 *
 * <p>{@code motivo} lleva el hallazgo concreto —anulada, sin saldo, de otro financiador, de otra
 * sede, fuera del periodo o en otra moneda—. Un solo tipo para los seis porque para la pantalla el
 * desenlace es el mismo: esa fila no entra, y el texto dice por que.
 */
public class ObligacionNoPresentableException extends RuntimeException {

	private final long obligacionId;
	private final String motivo;

	public ObligacionNoPresentableException(long obligacionId, String motivo) {
		super("La obligacion " + obligacionId + " no se puede presentar: " + motivo);
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
