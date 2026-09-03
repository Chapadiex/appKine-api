package com.akine.person.domain.exception;

/**
 * La autorizacion esta dada de baja y no admite la operacion (409).
 *
 * <p><b>Vencida, agotada y rechazada no son dada de baja.</b> Las tres son autorizaciones que
 * siguen siendo operables y consultables: lo que no habilitan es atender. Dar de baja significa
 * "esta autorizacion nunca debio cargarse", y es lo unico que esta excepcion cubre.
 */
public class AutorizacionInactivaException extends RuntimeException {

	private final long autorizacionId;
	private final String operacion;

	public AutorizacionInactivaException(long autorizacionId, String operacion) {
		super("La autorizacion esta dada de baja y no admite " + operacion);
		this.autorizacionId = autorizacionId;
		this.operacion = operacion;
	}

	public long getAutorizacionId() {
		return autorizacionId;
	}

	public String getOperacion() {
		return operacion;
	}
}
