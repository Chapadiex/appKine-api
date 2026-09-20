package com.akine.billing.domain.exception;

/**
 * La transicion no sale de ese estado. <b>409</b>.
 *
 * <p>Un solo tipo para toda la maquina de estados en vez de uno por transicion: para la pantalla el
 * desenlace es siempre el mismo —refrescar el lote y mirar en que quedo— y {@code esperado} le dice
 * al operador que hacia falta. Mismo criterio que {@code MovimientoNoReversibleException}.
 */
public class PresentacionEstadoInvalidoException extends RuntimeException {

	private final long presentacionId;
	private final String estadoActual;
	private final String esperado;

	public PresentacionEstadoInvalidoException(
			long presentacionId, String estadoActual, String esperado) {

		super("La presentacion " + presentacionId + " esta " + estadoActual
				+ " y la operacion exige " + esperado);
		this.presentacionId = presentacionId;
		this.estadoActual = estadoActual;
		this.esperado = esperado;
	}

	public long getPresentacionId() {
		return presentacionId;
	}

	public String getEstadoActual() {
		return estadoActual;
	}

	public String getEsperado() {
		return esperado;
	}
}
