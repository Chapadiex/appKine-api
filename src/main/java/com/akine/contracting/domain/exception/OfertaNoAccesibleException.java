package com.akine.contracting.domain.exception;

/**
 * La oferta de un arancel por oferta no existe en la sede del convenio, o es de otro tenant
 * (B-3, RF-M16-008). Se trata como no encontrada, igual que cualquier cross-tenant: 404.
 */
public class OfertaNoAccesibleException extends RuntimeException {

	private final long ofertaId;

	public OfertaNoAccesibleException(long ofertaId) {
		super("Oferta no accesible: " + ofertaId);
		this.ofertaId = ofertaId;
	}

	public long getOfertaId() {
		return ofertaId;
	}
}
