package com.akine.activity.domain.exception;

/**
 * 409 {@code conflict}: la oferta no puede sostener un pack —esta dada de baja, o no declara
 * {@code esquema_cobro = POR_PACK}—.
 *
 * <p>Lo segundo no es burocracia: RN-M29-005 dice que una asistencia solo consume credito cuando
 * la politica lo indica, y la politica es el esquema de la oferta. Un pack sobre una oferta
 * {@code POR_SESION} seria un credito que ninguna asistencia consume, o peor, uno que conviviera
 * con el devengo por atencion: el doble cobro.
 */
public class OfertaNoAdmitePackException extends RuntimeException {

	private final long ofertaId;
	private final String motivo;

	public OfertaNoAdmitePackException(long ofertaId, String motivo) {
		super("La oferta " + ofertaId + " no admite ese producto: " + motivo);
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
