package com.akine.notification.domain.exception;

import com.akine.notification.domain.OutboxStatus;

/** Solo se reintenta a mano lo que el worker ya dio por perdido: {@code FALLIDA} o {@code AGOTADA}. */
public class NotificacionNoReintentableException extends RuntimeException {

	private final long notificacionId;
	private final OutboxStatus estado;

	public NotificacionNoReintentableException(long notificacionId, OutboxStatus estado) {
		super("La notificacion " + notificacionId + " esta " + estado
				+ ": solo se reintenta una FALLIDA o AGOTADA");
		this.notificacionId = notificacionId;
		this.estado = estado;
	}

	public long getNotificacionId() {
		return notificacionId;
	}

	public OutboxStatus getEstado() {
		return estado;
	}
}
