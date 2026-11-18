package com.akine.platform.spi.modelo;

/**
 * El modelo de lenguaje no responde, responde con error o se vencio el plazo. Cada modulo la traduce
 * a su propio problema tipado en su borde.
 */
public class ModeloNoDisponibleException extends RuntimeException {

	public ModeloNoDisponibleException() {
		super("El modelo de lenguaje no esta disponible.");
	}

	public ModeloNoDisponibleException(Throwable causa) {
		super("El modelo de lenguaje no esta disponible.", causa);
	}
}
