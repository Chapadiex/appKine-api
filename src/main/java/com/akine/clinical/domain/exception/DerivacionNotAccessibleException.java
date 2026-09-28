package com.akine.clinical.domain.exception;

/**
 * La derivacion no existe o es de otra organizacion (404).
 *
 * <p>Los dos casos colapsan a proposito: distinguirlos confirmaria que ese id existe, y bastaria
 * probar ids consecutivos para enumerar las derivaciones del sistema. Cross-tenant es <b>404</b>,
 * nunca 403.
 */
public class DerivacionNotAccessibleException extends RuntimeException {

	private final long derivacionId;

	public DerivacionNotAccessibleException(long derivacionId) {
		super("La derivacion " + derivacionId + " no existe o no es accesible");
		this.derivacionId = derivacionId;
	}

	public long getDerivacionId() {
		return derivacionId;
	}
}
