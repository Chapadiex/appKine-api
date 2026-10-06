package com.akine.notification.domain.exception;

/**
 * La notificacion no existe, o no es del tenant del actor. Las dos se responden igual —404— para
 * no confirmar que existe una notificacion de otra organizacion.
 */
public class NotificacionNoEncontradaException extends RuntimeException {

	private final long notificacionId;

	public NotificacionNoEncontradaException(long notificacionId) {
		super("Notificacion no encontrada: " + notificacionId);
		this.notificacionId = notificacionId;
	}

	public long getNotificacionId() {
		return notificacionId;
	}
}
