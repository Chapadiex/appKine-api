package com.akine.notification.infrastructure;

import com.akine.notification.domain.EmailMessage;
import com.akine.notification.domain.ErrorSanitizer;
import com.akine.notification.domain.port.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptador de desarrollo: escribe el envio en el log estructurado y no manda nada.
 *
 * <p>Es el activo por defecto ({@code akine.notification.email.mode=log}) y el unico que
 * existe completo en 01.02, para que el circuito —encolar, reclamar, entregar, marcar
 * ENVIADA— se pueda ejercitar de punta a punta sin un servidor de correo.
 *
 * <p><b>No loguea el enlace ni el cuerpo.</b> El diseño original preveia imprimir el enlace
 * completo en local para facilitar el QA manual; no se hace. Un enlace de activacion en el log
 * es la misma credencial persistida que T-11 saca de la tabla, con el agravante de que los
 * logs se envian a un agregador y se retienen. Lo que si queda es todo lo necesario para
 * verificar el circuito: tipo, destinatario enmascarado y asunto. Quien necesite el enlace en
 * desarrollo lo obtiene de la base de {@code identity}, que es su dueño.
 */
public class LogEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);

	private final String remitente;

	public LogEmailSender(String remitente) {
		this.remitente = remitente;
	}

	@Override
	public void send(EmailMessage mensaje) {
		log.info("[email:log] envio simulado de={} para={} asunto=\"{}\" cuerpo=<omitido {} caracteres>",
				remitente,
				ErrorSanitizer.maskEmail(mensaje.destinatario()),
				mensaje.asunto(),
				mensaje.cuerpo().length());
	}

	@Override
	public String nombre() {
		return "log";
	}
}
