package com.akine.notification.domain.exception;

/**
 * Fallo al entregar un mail.
 *
 * <p>Distingue <b>transitorio</b> de <b>permanente</b>, y esa distincion es lo unico que el
 * worker necesita saber para decidir: un SMTP caido o un timeout se reintentan; un
 * destinatario invalido, un template inexistente o un token ya consumido no, porque el
 * resultado del reintento seria identico y solo gastaria la cuota. Sin esta distincion, o se
 * reintenta lo que nunca va a funcionar o se descarta lo que habria andado al segundo intento.
 *
 * <p>El mensaje de esta excepcion NO se guarda tal cual: pasa por {@code ErrorSanitizer} antes
 * de tocar la base.
 */
public class EmailDeliveryException extends RuntimeException {

	private final boolean transitorio;

	private EmailDeliveryException(String mensaje, Throwable causa, boolean transitorio) {
		super(mensaje, causa);
		this.transitorio = transitorio;
	}

	/** Fallo que puede resolverse solo: SMTP caido, timeout, rate limit del proveedor. */
	public static EmailDeliveryException transitorio(String mensaje, Throwable causa) {
		return new EmailDeliveryException(mensaje, causa, true);
	}

	/** Fallo que no cambia al reintentar: direccion invalida, template inexistente. */
	public static EmailDeliveryException permanente(String mensaje) {
		return new EmailDeliveryException(mensaje, null, false);
	}

	public boolean esTransitorio() {
		return transitorio;
	}
}
