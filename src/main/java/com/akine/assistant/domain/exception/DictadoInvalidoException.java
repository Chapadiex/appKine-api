package com.akine.assistant.domain.exception;

/** El audio o el texto que llego no se puede dictar: vacio, demasiado grande o de otro tipo (400). */
public class DictadoInvalidoException extends RuntimeException {

	public DictadoInvalidoException(String mensaje) {
		super(mensaje);
	}
}
