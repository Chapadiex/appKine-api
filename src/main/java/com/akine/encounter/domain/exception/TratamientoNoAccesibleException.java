package com.akine.encounter.domain.exception;

/**
 * El tratamiento no existe, o no es de esa sesion, de ese tenant o de esa sede. <b>404 siempre.</b>
 *
 * <p>Cross-tenant es 404 y nunca 403: un 403 confirmaria que el id existe y bastaria probar ids
 * consecutivos para censar las intervenciones de otro centro. En un modulo clinico eso deja de ser
 * aislamiento de tenant y pasa a ser privacidad.
 */
public class TratamientoNoAccesibleException extends RuntimeException {

	private final long tratamientoId;

	public TratamientoNoAccesibleException(long tratamientoId) {
		super("Tratamiento no accesible: " + tratamientoId);
		this.tratamientoId = tratamientoId;
	}

	public long getTratamientoId() {
		return tratamientoId;
	}
}
