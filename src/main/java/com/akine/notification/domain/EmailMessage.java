package com.akine.notification.domain;

/**
 * Un mail listo para entregar.
 *
 * <p>Es EFIMERO por contrato: se construye en memoria en el momento del envio y no se
 * persiste. Es el unico lugar de todo el modulo donde el enlace seguro existe en claro, y
 * existe solo el tiempo que dura la llamada al adaptador (T-11).
 *
 * @param destinatario email destino
 * @param asunto       asunto del mensaje
 * @param cuerpo       texto plano; en 01.02 no hay HTML
 */
public record EmailMessage(String destinatario, String asunto, String cuerpo) {

	public EmailMessage {
		if (destinatario == null || destinatario.isBlank()) {
			throw new IllegalArgumentException("El mensaje necesita destinatario");
		}
		if (asunto == null || asunto.isBlank()) {
			throw new IllegalArgumentException("El mensaje necesita asunto");
		}
		if (cuerpo == null || cuerpo.isBlank()) {
			throw new IllegalArgumentException("El mensaje necesita cuerpo");
		}
	}

	/**
	 * Representacion segura para logs: destinatario enmascarado y SIN cuerpo.
	 *
	 * <p>El {@code toString} por defecto de un record imprime todos los campos, y el cuerpo
	 * lleva el enlace. Por eso se sobreescribe: la unica forma de que un mensaje termine
	 * entero en el log es que alguien lo escriba a mano.
	 */
	@Override
	public String toString() {
		return "EmailMessage[destinatario=" + ErrorSanitizer.maskEmail(destinatario)
				+ ", asunto=" + asunto + ", cuerpo=<omitido>]";
	}
}
