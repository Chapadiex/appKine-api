package com.akine.contracting.domain.exception;

/**
 * Ya hay un financiador VIGENTE con ese CUIT en la organizacion.
 *
 * <p>Es un conflicto propio y no una variante del de codigo: el operador que lo recibe tiene que
 * entender que la obra social ya esta cargada con OTRO codigo, no que eligio mal el suyo.
 */
public class FinanciadorCuitTakenException extends RuntimeException {

	public FinanciadorCuitTakenException() {
		super("CUIT de financiador en uso");
	}
}
