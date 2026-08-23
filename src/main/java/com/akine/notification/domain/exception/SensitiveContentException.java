package com.akine.notification.domain.exception;

/**
 * Alguien intento guardar en el outbox algo que no puede estar guardado ahi: un token, un
 * enlace, una URL o un valor con pinta de secreto (T-11, RN-M02-003).
 *
 * <p>Extiende {@link IllegalArgumentException} porque es un error de programacion del modulo
 * productor, no una condicion de negocio: se corrige en el codigo que encola, no reintentando.
 *
 * <p><b>El mensaje nunca incluye el valor ofensivo.</b> Si lo incluyera, el token terminaria
 * en el log de errores, que es justo el lugar del que lo estamos sacando.
 */
public class SensitiveContentException extends IllegalArgumentException {

	public SensitiveContentException(String clave, String motivo) {
		super("El campo '" + clave + "' no puede guardarse en el outbox: " + motivo
				+ ". El outbox jamas almacena tokens ni enlaces (T-11): pasa la referencia "
				+ "opaca al token y deja que el enlace se reconstruya al enviar");
	}
}
