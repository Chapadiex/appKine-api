package com.akine.person.domain.exception;

/**
 * La autorizacion no existe, es de otra organizacion o es de otro paciente (404).
 *
 * <p>Los tres casos responden lo MISMO, por el mismo motivo que la orden: distinguirlos
 * confirmaria que ese id existe.
 */
public class AutorizacionNotAccessibleException extends RuntimeException {

	private final long autorizacionId;

	public AutorizacionNotAccessibleException(long autorizacionId) {
		super("La autorizacion no es accesible en este contexto");
		this.autorizacionId = autorizacionId;
	}

	public long getAutorizacionId() {
		return autorizacionId;
	}
}
