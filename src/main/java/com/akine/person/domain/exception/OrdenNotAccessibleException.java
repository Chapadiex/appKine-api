package com.akine.person.domain.exception;

/**
 * La orden medica no existe, es de otra organizacion o es de otro paciente (404).
 *
 * <p>Los tres casos responden lo MISMO. Distinguir "es de otro paciente" de "no existe"
 * convertiria la ruta en un oraculo de ids: bastaria probar consecutivos para saber cuales
 * existen. Es el mismo criterio de 01.01 —cross-tenant es 404, nunca 403— aplicado tambien
 * dentro del tenant.
 */
public class OrdenNotAccessibleException extends RuntimeException {

	private final long ordenId;

	public OrdenNotAccessibleException(long ordenId) {
		super("La orden medica no es accesible en este contexto");
		this.ordenId = ordenId;
	}

	public long getOrdenId() {
		return ordenId;
	}
}
