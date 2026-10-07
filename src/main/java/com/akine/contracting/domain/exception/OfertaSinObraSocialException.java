package com.akine.contracting.domain.exception;

/**
 * La oferta no admite obra social (M27), asi que un arancel de convenio para ella no se aplicaria
 * nunca: el devengo y la cobertura aplicable la cobran particular (B-3, RF-M16-008).
 */
public class OfertaSinObraSocialException extends RuntimeException {

	private final long ofertaId;

	public OfertaSinObraSocialException(long ofertaId) {
		super("La oferta " + ofertaId + " no admite obra social");
		this.ofertaId = ofertaId;
	}

	public long getOfertaId() {
		return ofertaId;
	}
}
