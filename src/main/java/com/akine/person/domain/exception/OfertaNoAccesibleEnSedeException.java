package com.akine.person.domain.exception;

/**
 * La oferta por la que se pregunta la cobertura aplicable no existe en la sede del contexto, o es
 * de otro tenant (B-3, RF-M08-006). 404, como cualquier cross-tenant.
 */
public class OfertaNoAccesibleEnSedeException extends RuntimeException {

	private final long ofertaId;

	public OfertaNoAccesibleEnSedeException(long ofertaId) {
		super("Oferta no accesible en la sede: " + ofertaId);
		this.ofertaId = ofertaId;
	}

	public long getOfertaId() {
		return ofertaId;
	}
}
