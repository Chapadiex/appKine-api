package com.akine.organization.domain.exception;

/** El pedido de prueba no existe (404). */
public class SolicitudDePruebaNotFoundException extends RuntimeException {

	public SolicitudDePruebaNotFoundException(long id) {
		super("Solicitud de prueba inexistente: " + id);
	}
}
