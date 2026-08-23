package com.akine.notification.domain.port;

import com.akine.notification.domain.EmailMessage;
import com.akine.notification.domain.exception.EmailDeliveryException;

/**
 * Canal de salida de un mail.
 *
 * <p>Vive en {@code domain.port} y no en {@code spi} a proposito: ningun otro modulo tiene por
 * que mandar un mail por su cuenta. Los demas modulos encolan por
 * {@code notification.spi.NotificationOutbox} y el envio es asunto interno. Si este puerto
 * estuviera en {@code spi}, cualquier modulo podria saltearse el outbox —y con el, la garantia
 * de RN-M26-001— inyectando el sender directo.
 *
 * <p>Implementaciones en {@code notification.infrastructure}: una de log estructurado para
 * desarrollo local y el punto de extension para la SMTP real.
 */
public interface EmailSender {

	/**
	 * Entrega el mensaje.
	 *
	 * @throws EmailDeliveryException con {@code transitorio=true} si el reintento tiene
	 *                                sentido, {@code false} si no lo tiene
	 */
	void send(EmailMessage mensaje);

	/** Nombre del adaptador, para logs y para el diagnostico de arranque. */
	String nombre();
}
