package com.akine.identity.domain.exception;

/**
 * La cuenta autenticada no acepto la version vigente de los terminos (19/11/2026).
 *
 * <p>Sale como 403 {@code terminos-pendientes}: la sesion sigue valida y lo unico que la destraba
 * es aceptar con {@code POST /api/v1/me/terminos}.
 */
public class TerminosPendientesException extends RuntimeException {

	private final String versionVigente;

	public TerminosPendientesException(String versionVigente) {
		super("Hay que aceptar la version vigente de los terminos y condiciones para seguir");
		this.versionVigente = versionVigente;
	}

	public String getVersionVigente() {
		return versionVigente;
	}
}
