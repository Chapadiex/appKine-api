package com.akine.knowledge.domain.manual;

/** Una ficha del manual no respeta el formato: hace fallar el arranque, no una consulta. */
public class ManualInvalidoException extends RuntimeException {

	public ManualInvalidoException(String mensaje) {
		super(mensaje);
	}

	public ManualInvalidoException(String mensaje, Throwable causa) {
		super(mensaje, causa);
	}
}
