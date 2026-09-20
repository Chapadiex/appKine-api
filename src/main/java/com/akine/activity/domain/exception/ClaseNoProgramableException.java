package com.akine.activity.domain.exception;

/**
 * La oferta no puede sostener una clase (RN-M28-001). <b>409 y no 404</b>: la oferta existe y quien
 * programa la esta viendo en la lista.
 *
 * <p>El motivo viaja porque decide que puede ofrecer la pantalla: "no es grupal" manda a elegir
 * otra oferta, "no esta vigente" manda a mover la fecha.
 */
public class ClaseNoProgramableException extends RuntimeException {

	private final long ofertaId;
	private final String motivo;

	public ClaseNoProgramableException(long ofertaId, String motivo) {
		super("La oferta " + ofertaId + " no puede sostener una clase: " + motivo);
		this.ofertaId = ofertaId;
		this.motivo = motivo;
	}

	public long getOfertaId() {
		return ofertaId;
	}

	public String getMotivo() {
		return motivo;
	}
}
