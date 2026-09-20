package com.akine.billing.domain.exception;

/**
 * El lote ya salio del centro: no se le agregan ni se le quitan items. <b>409</b>.
 *
 * <p>Una presentacion confirmada existe del otro lado del mostrador. Quitarle una prestacion seria
 * cambiar lo que se reclamo despues de reclamarlo, sin que el financiador se entere. Lo que
 * corresponde es un <b>debito</b> (RF-M21-006), que deja motivo, actor e instante.
 */
public class PresentacionNoEditableException extends RuntimeException {

	private final long presentacionId;
	private final String estado;

	public PresentacionNoEditableException(long presentacionId, String estado) {
		super("La presentacion " + presentacionId + " ya no es un borrador: esta " + estado);
		this.presentacionId = presentacionId;
		this.estado = estado;
	}

	public long getPresentacionId() {
		return presentacionId;
	}

	public String getEstado() {
		return estado;
	}
}
