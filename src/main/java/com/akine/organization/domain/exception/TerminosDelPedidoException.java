package com.akine.organization.domain.exception;

/**
 * El pedido de prueba no acepto la version vigente de los terminos (19/11/2026).
 *
 * <p>Sale como 400 {@code validation-error} con el campo en {@code errors}, igual que el alta de
 * una cuenta, para que el formulario de la landing lo marque como cualquier otro campo.
 */
public class TerminosDelPedidoException extends RuntimeException {

	private final String campo;

	public TerminosDelPedidoException(String campo, String mensaje) {
		super(mensaje);
		this.campo = campo;
	}

	public String getCampo() {
		return campo;
	}
}
