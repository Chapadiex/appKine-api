package com.akine.notification.application;

import com.akine.notification.domain.SanitizedPayload;
import com.akine.notification.spi.NotificationType;

/**
 * Una notificacion ya reclamada por el worker, lista para intentar entregarse.
 *
 * <p>Es una COPIA inmutable de lo que hace falta para redactar y mandar el mail, tomada dentro
 * de la transaccion de claim. El envio ocurre fuera de esa transaccion —un SMTP lento no puede
 * mantener filas bloqueadas— y trabajar sobre la entity fuera de su sesion seria pedir un
 * lazy loading fallido o una escritura accidental.
 *
 * <p>No lleva enlace ni token: el enlace se reconstruye justo antes de redactar (T-11).
 */
public record PendingDelivery(
		long id,
		NotificationType tipo,
		String destinatario,
		SanitizedPayload payload,
		String referenciaTokenId,
		int intentos) {
}
