package com.akine.activity.domain.exception;

/** La oferta no existe en esa sede. 404, por el mismo motivo que el resto de los no accesibles. */
public class OfertaNotAccessibleException extends RuntimeException {

	private final long ofertaId;

	public OfertaNotAccessibleException(long ofertaId) {
		super("La oferta " + ofertaId + " no existe en esta sede");
		this.ofertaId = ofertaId;
	}

	public long getOfertaId() {
		return ofertaId;
	}
}
