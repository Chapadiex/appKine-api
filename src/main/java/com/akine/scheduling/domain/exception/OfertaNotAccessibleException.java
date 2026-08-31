package com.akine.scheduling.domain.exception;

/**
 * La oferta no existe, o existe en otro tenant o en otra sede. <b>404 siempre</b>.
 *
 * <p>No se distingue "no existe" de "es de otro tenant" a proposito: un 403 en el segundo caso
 * confirmaria que esa oferta existe, y eso alcanza para enumerar el catalogo de un competidor
 * probando ids.
 */
public class OfertaNotAccessibleException extends RuntimeException {

	private final long ofertaId;

	public OfertaNotAccessibleException(long ofertaId) {
		super("Oferta no accesible: " + ofertaId);
		this.ofertaId = ofertaId;
	}

	public long getOfertaId() {
		return ofertaId;
	}
}
