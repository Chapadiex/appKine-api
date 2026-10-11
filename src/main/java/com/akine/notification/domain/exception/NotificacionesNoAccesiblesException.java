package com.akine.notification.domain.exception;

/**
 * La organizacion de la ruta no es la del contexto del request.
 *
 * <p>Se traduce a {@code 404}, igual que una organizacion inexistente: con el contexto de A, las
 * notificaciones de B no existen, aunque la cuenta tambien administre B.
 */
public class NotificacionesNoAccesiblesException extends RuntimeException {

	public NotificacionesNoAccesiblesException() {
		super("Las notificaciones pedidas no son de la organizacion del contexto");
	}
}
