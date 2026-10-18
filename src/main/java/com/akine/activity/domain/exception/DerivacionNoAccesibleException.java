package com.akine.activity.domain.exception;

/**
 * La derivacion no existe, es de otro tenant o no cuelga de la participacion de la ruta (404
 * {@code derivacion-no-accesible}). Los tres casos colapsan: distinguirlos confirmaria que el id
 * existe.
 */
public class DerivacionNoAccesibleException extends RuntimeException {

	private final long derivacionId;

	public DerivacionNoAccesibleException(long derivacionId) {
		super("La derivacion " + derivacionId + " no existe en este alcance");
		this.derivacionId = derivacionId;
	}

	public long getDerivacionId() {
		return derivacionId;
	}
}
