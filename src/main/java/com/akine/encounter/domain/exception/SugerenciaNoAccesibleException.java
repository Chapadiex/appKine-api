package com.akine.encounter.domain.exception;

/**
 * La sugerencia de evolucion no existe, es de otra organizacion o de otro caso. <b>404</b>, las tres
 * indistinguibles a proposito.
 */
public class SugerenciaNoAccesibleException extends RuntimeException {

	public SugerenciaNoAccesibleException(long sugerenciaId) {
		super("La sugerencia " + sugerenciaId + " no existe");
	}
}
