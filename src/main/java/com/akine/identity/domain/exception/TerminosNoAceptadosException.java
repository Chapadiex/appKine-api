package com.akine.identity.domain.exception;

/**
 * El alta no acepto la version vigente de los terminos (DP-45). Se traduce a
 * {@code 400 validation-error} con el campo en {@code errors}.
 *
 * <p>No dice nada sobre la cuenta: se evalua antes de mirar si el email existe (ADR-0018).
 */
public class TerminosNoAceptadosException extends RuntimeException {

	private final String campo;

	public TerminosNoAceptadosException(String campo, String mensaje) {
		super(mensaje);
		this.campo = campo;
	}

	public String getCampo() {
		return campo;
	}
}
