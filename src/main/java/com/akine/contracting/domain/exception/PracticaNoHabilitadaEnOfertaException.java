package com.akine.contracting.domain.exception;

/**
 * El arancel por oferta pide una practica que la oferta no declara (B-3, RF-M16-008; A-9, DP-11).
 *
 * <p>Al registrar un tratamiento, una practica no declarada es una alerta y no un rechazo (DP-11),
 * porque frenar un acto clinico por una regla administrativa seria peor. Aca no hay acto clinico:
 * es configuracion, y un arancel pactado para una practica que la oferta no presta es un error de
 * carga que conviene ver ahora.
 */
public class PracticaNoHabilitadaEnOfertaException extends RuntimeException {

	private final long practicaId;
	private final long ofertaId;

	public PracticaNoHabilitadaEnOfertaException(long practicaId, long ofertaId) {
		super("La practica " + practicaId + " no esta declarada en la oferta " + ofertaId);
		this.practicaId = practicaId;
		this.ofertaId = ofertaId;
	}

	public long getPracticaId() {
		return practicaId;
	}

	public long getOfertaId() {
		return ofertaId;
	}
}
