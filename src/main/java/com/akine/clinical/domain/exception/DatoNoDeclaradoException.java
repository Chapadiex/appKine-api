package com.akine.clinical.domain.exception;

/** El dato no es declarado: no lleva revision (DP-47, 409). */
public class DatoNoDeclaradoException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public DatoNoDeclaradoException(String mensaje) {
		super(mensaje);
	}
}
