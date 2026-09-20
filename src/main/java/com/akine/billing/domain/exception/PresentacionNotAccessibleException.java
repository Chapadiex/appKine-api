package com.akine.billing.domain.exception;

/**
 * Esa presentacion no existe, o es de otro tenant. <b>404</b>.
 *
 * <p>Cross-tenant es 404 y nunca 403: un 403 confirma que existe, y bastaria probar ids
 * consecutivos para enumerar los lotes del SaaS.
 */
public class PresentacionNotAccessibleException extends RuntimeException {

	private final long presentacionId;

	public PresentacionNotAccessibleException(long presentacionId) {
		super("La presentacion " + presentacionId + " no existe o no es accesible");
		this.presentacionId = presentacionId;
	}

	public long getPresentacionId() {
		return presentacionId;
	}
}
