package com.akine.activity.domain.exception;

/**
 * La oferta de la clase no genera registro clinico, asi que su participacion no se deriva (409
 * {@code oferta-no-clinica}). RF-M09-007 y CA-M09-007-06.
 *
 * <p>Es el <b>gate duro</b> de AKINE-08.04 y el primero que se evalua: se decide antes de resolver
 * la persona, para que una clase de Yoga no deje ningun rastro clinico.
 */
public class OfertaNoClinicaException extends RuntimeException {

	private final long ofertaId;

	public OfertaNoClinicaException(long ofertaId) {
		super("La oferta " + ofertaId + " no genera registro clinico: su participacion no se deriva");
		this.ofertaId = ofertaId;
	}

	public long getOfertaId() {
		return ofertaId;
	}
}
