package com.akine.billing.domain.exception;

/**
 * La oferta a la que se quiere imputar un egreso no existe en la sede del egreso (DP-39). <b>404</b>,
 * como cualquier referencia de otro tenant: un 403 o un 409 confirmarian que existe en otro lado.
 *
 * <p>Una oferta de <b>otra sede</b> de la misma organizacion tambien cae aca: el egreso se paga desde
 * la caja de una sede y su costo se lee en el reporte de esa sede.
 */
public class OfertaDelEgresoNoAccesibleException extends RuntimeException {

	private final long ofertaId;

	public OfertaDelEgresoNoAccesibleException(long ofertaId) {
		super("La oferta " + ofertaId + " no existe en la sede del egreso");
		this.ofertaId = ofertaId;
	}

	public long getOfertaId() {
		return ofertaId;
	}
}
