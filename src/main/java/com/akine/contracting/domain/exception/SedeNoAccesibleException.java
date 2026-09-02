package com.akine.contracting.domain.exception;

/**
 * La sede de la ruta no existe o es de otra organizacion. Responde 404, como todo cross-tenant.
 *
 * <p>Se comprueba <b>antes</b> de evaluar el permiso: si se evaluara primero, una sede ajena
 * devolveria 403 y eso confirmaria que ese id existe en otro tenant.
 */
public class SedeNoAccesibleException extends RuntimeException {

	private final long consultorioId;

	public SedeNoAccesibleException(long consultorioId) {
		super("Sede no accesible: " + consultorioId);
		this.consultorioId = consultorioId;
	}

	public long getConsultorioId() {
		return consultorioId;
	}
}
