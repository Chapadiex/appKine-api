package com.akine.billing.domain.exception;

/**
 * No se confirma un lote sin items. <b>400</b>.
 *
 * <p>Un reclamo por cero pesos es ruido en la cuenta corriente del financiador, y ademas consumiria
 * un numero de la serie para no decir nada.
 */
public class PresentacionVaciaException extends RuntimeException {

	private final long presentacionId;

	public PresentacionVaciaException(long presentacionId) {
		super("La presentacion " + presentacionId + " no tiene prestaciones que reclamar");
		this.presentacionId = presentacionId;
	}

	public long getPresentacionId() {
		return presentacionId;
	}
}
