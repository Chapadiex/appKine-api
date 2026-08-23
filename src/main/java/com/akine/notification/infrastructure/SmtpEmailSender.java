package com.akine.notification.infrastructure;

import com.akine.notification.domain.EmailMessage;
import com.akine.notification.domain.port.EmailSender;

/**
 * PUNTO DE EXTENSION del adaptador SMTP real. Hoy no esta implementado y falla al construirse.
 *
 * <p><b>Por que falla al arrancar y no al enviar.</b> Un adaptador que acepta el mensaje y
 * despues lo descarta —o que lo marca FALLIDA en silencio— hace creer que el circuito funciona
 * mientras las invitaciones y los resets se pierden de a uno. Si alguien configura
 * {@code akine.notification.email.mode=smtp}, la aplicacion no levanta y el motivo esta en la
 * primera linea del error. Es la unica falla honesta.
 *
 * <p><b>Que falta para completarlo</b>, todo dentro de esta clase y de
 * {@link NotificationConfig}:
 *
 * <ol>
 *   <li>Agregar {@code spring-boot-starter-mail} al {@code pom.xml} —hoy no esta y este modulo
 *       no puede tocar el pom—, e inyectar {@code JavaMailSender} por constructor.</li>
 *   <li>Host, puerto, usuario y contrasena por variable de entorno bajo
 *       {@code spring.mail.*}. Jamas versionados.</li>
 *   <li>Traducir las excepciones de JavaMail a
 *       {@code EmailDeliveryException.transitorio(...)} —{@code MailSendException} por
 *       conexion o timeout, rate limit del proveedor— o
 *       {@code EmailDeliveryException.permanente(...)} —direccion invalida, 5xx del SMTP—.
 *       Esa distincion es lo unico que el worker necesita y no la puede inferir solo.</li>
 *   <li>Nunca incluir el cuerpo del mensaje en el mensaje de la excepcion: el cuerpo lleva el
 *       enlace, y la excepcion termina —sanitizada, pero termina— en {@code error_sanitizado}
 *       (T-11).</li>
 *   <li>Un test de contrato compartido con {@link LogEmailSender} y un test de integracion
 *       contra GreenMail que verifique asunto y destinatario.</li>
 * </ol>
 *
 * <p>El resto del modulo ya esta listo para recibirlo: nadie fuera de {@link NotificationConfig}
 * conoce la implementacion concreta, todos dependen del puerto {@code EmailSender}.
 */
public class SmtpEmailSender implements EmailSender {

	/** Mensaje unico del fallo de arranque; tambien lo verifica el test. */
	static final String NO_IMPLEMENTADO =
			"El adaptador SMTP todavia no esta implementado (AKINE-01.02). "
					+ "Configura akine.notification.email.mode=log o implementa SmtpEmailSender: "
					+ "requiere spring-boot-starter-mail en el pom y credenciales por entorno.";

	public SmtpEmailSender() {
		throw new IllegalStateException(NO_IMPLEMENTADO);
	}

	@Override
	public void send(EmailMessage mensaje) {
		throw new IllegalStateException(NO_IMPLEMENTADO);
	}

	@Override
	public String nombre() {
		return "smtp";
	}
}
