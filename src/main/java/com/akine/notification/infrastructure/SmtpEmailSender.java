package com.akine.notification.infrastructure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import com.akine.notification.domain.EmailMessage;
import com.akine.notification.domain.ErrorSanitizer;
import com.akine.notification.domain.exception.EmailDeliveryException;
import com.akine.notification.domain.port.EmailSender;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Adaptador SMTP real: el unico canal por el que un correo de AKINE sale de verdad.
 *
 * <h2>Por que existe</h2>
 *
 * <p>Hasta AKINE-01.02 el unico adaptador completo era {@link LogEmailSender}, que registra el
 * envio y no manda nada. Con eso, ADR-0018 quedaba a medias: toda su estrategia de respuestas
 * uniformes —el {@code 202} que no revela si un email esta registrado— se apoya en que la
 * persona legitima reciba el correo y siga por ahi. Sin canal, el {@code 202} uniforme deja al
 * usuario sin salida: no puede activar la cuenta ni recuperar la contrasena.
 *
 * <h2>Lo unico que este adaptador decide</h2>
 *
 * <p><b>Transitorio o permanente, y nada mas.</b> La politica de reintentos —backoff, jitter,
 * presupuesto de intentos, agotamiento— vive completa en el outbox
 * ({@code OutboxDispatchService} + {@code RetryBackoffPolicy}) y no se toca desde aca. Este
 * adaptador intenta UN envio y traduce el fallo a la unica pregunta que el worker no puede
 * responder solo: ¿tiene sentido volver a intentarlo?
 *
 * <p>El criterio, en orden de autoridad:
 *
 * <ol>
 *   <li><b>La direccion no es una direccion</b> (falla al parsearse): permanente, y ni siquiera
 *       se abre la conexion. Reintentar un texto que no es un email da lo mismo cinco veces.</li>
 *   <li><b>Autenticacion rechazada</b>: transitorio, y se decide <b>antes</b> que el codigo de
 *       respuesta porque llega como {@code 535}, o sea disfrazada de rechazo definitivo del
 *       mensaje. No lo es: el mensaje esta bien, lo que esta mal es la credencial del relay. La
 *       decision es deliberada y discutible: marcandolo permanente, cada correo emitido durante
 *       el incidente se pierde con estado FALLIDA, que segun {@code SecureLinkResolver} significa
 *       "no hay nada que revisar". Marcandolo transitorio, los mensajes se reintentan mientras
 *       alguien arregla la credencial y, si no llega a tiempo, quedan AGOTADA, que es
 *       precisamente el estado que le dice al operador "revisa el proveedor de correo".</li>
 *   <li><b>El codigo de respuesta del servidor</b>, que es la senal estandar y la misma en todo
 *       proveedor: {@code 4xx} transitorio (buzon ocupado, rate limit, "try again later"),
 *       {@code 5xx} permanente (destinatario inexistente, rechazo definitivo).</li>
 *   <li><b>{@code SendFailedException} sin codigo legible</b>: si el servidor marco la direccion
 *       como invalida y no dejo ninguna valida sin enviar, permanente; si quedaron direcciones
 *       validas sin enviar, transitorio.</li>
 *   <li><b>Red</b> —conexion rechazada, host desconocido, timeout, {@code IOException}—:
 *       transitorio.</li>
 *   <li><b>Mensaje mal formado</b> ({@code MailParseException}): permanente.</li>
 *   <li><b>Cualquier otra cosa</b>: transitorio. Es el mismo criterio conservador que ya aplica
 *       {@code OutboxDispatcher} a lo inesperado: descartar por las dudas un correo que habria
 *       llegado al segundo intento es peor que gastar cuatro reintentos.</li>
 * </ol>
 *
 * <h2>Lo que nunca sale de aca</h2>
 *
 * <p>Ni el cuerpo ni el enlace, en ningun log y en ningun mensaje de excepcion (RN-M02-003,
 * T-11). El cuerpo lleva el enlace de un solo uso, y el mensaje de la excepcion termina
 * —sanitizado, pero termina— en la columna {@code error_sanitizado}, que se lee desde una
 * pantalla administrativa y viaja a los backups. Por eso los mensajes de fallo se redactan aca
 * a mano en vez de reenviar el texto crudo de JavaMail, y el destinatario va siempre
 * enmascarado.
 */
public class SmtpEmailSender implements EmailSender {

	private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

	/**
	 * Codigo de respuesta SMTP al principio del mensaje del servidor.
	 *
	 * <p>Formato de RFC 5321: tres digitos, seguidos de espacio o guion. Se busca solo al
	 * comienzo —{@code "550 5.1.1 User unknown"}— y nunca en medio del texto, para no confundir
	 * un numero cualquiera de un mensaje libre con un codigo de respuesta.
	 */
	private static final Pattern CODIGO_SMTP = Pattern.compile("^\\s*([2-5]\\d{2})(?:[ -]|$)");

	/** Cuantos niveles de causa se recorren buscando la senal. Mas que esto es ruido. */
	private static final int PROFUNDIDAD_MAXIMA_DE_CAUSAS = 10;

	private final JavaMailSender mailSender;
	private final String remitente;

	/**
	 * @param remitente direccion del {@code From}; ya validada por quien arma la configuracion
	 */
	public SmtpEmailSender(JavaMailSender mailSender, String remitente) {
		if (mailSender == null) {
			throw new IllegalStateException("El adaptador SMTP necesita un JavaMailSender");
		}
		if (remitente == null || remitente.isBlank()) {
			throw new IllegalStateException(
					"El adaptador SMTP necesita un remitente: configurar AKINE_MAIL_FROM "
							+ "(propiedad akine.notification.email.from).");
		}
		this.mailSender = mailSender;
		this.remitente = remitente;
	}

	@Override
	public void send(EmailMessage mensaje) {
		String destinatarioEnmascarado = ErrorSanitizer.maskEmail(mensaje.destinatario());
		InternetAddress destino = parsearDestinatario(mensaje.destinatario(), destinatarioEnmascarado);
		try {
			mailSender.send(redactar(mensaje, destino));
		} catch (RuntimeException e) {
			throw clasificar(e, destinatarioEnmascarado);
		}
		// Sin cuerpo y sin asunto: el asunto de un mail de recuperacion ya dice demasiado
		// junto al destinatario. Lo que hace falta para operar es que salio y para quien.
		log.debug("[email:smtp] mensaje entregado al relay para={}", destinatarioEnmascarado);
	}

	@Override
	public String nombre() {
		return "smtp";
	}

	/**
	 * Valida la direccion ANTES de abrir la conexion.
	 *
	 * <p>Que el rechazo ocurra aca y no en el servidor tiene dos ventajas: el fallo es
	 * permanente sin ambiguedad —no depende de como conteste el relay de turno— y no se gasta
	 * una conexion ni una linea de cuota en un texto que jamas va a ser una direccion.
	 */
	private InternetAddress parsearDestinatario(String destinatario, String enmascarado) {
		try {
			InternetAddress direccion = new InternetAddress(destinatario, true);
			direccion.validate();
			return direccion;
		} catch (AddressException e) {
			throw EmailDeliveryException.permanente(
					"La direccion de destino no es una direccion de correo valida: " + enmascarado);
		}
	}

	/** Arma el MIME. UTF-8 explicito: sin eso las tildes del template llegan rotas. */
	private MimeMessage redactar(EmailMessage mensaje, InternetAddress destino) {
		MimeMessage mime = mailSender.createMimeMessage();
		try {
			MimeMessageHelper helper =
					new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
			helper.setFrom(remitente);
			helper.setTo(destino);
			helper.setSubject(mensaje.asunto());
			helper.setText(mensaje.cuerpo(), false);
			return mime;
		} catch (MessagingException e) {
			// Un MIME que no se puede armar no se arma mejor al quinto intento.
			throw EmailDeliveryException.permanente(
					"No se pudo redactar el mensaje MIME: " + e.getClass().getSimpleName());
		}
	}

	/**
	 * La unica decision del adaptador.
	 *
	 * <p>Es {@code static} y {@code package-private} a proposito: se prueba sola, sin servidor
	 * y sin Spring, alimentandola con las excepciones que un relay real produce.
	 */
	static EmailDeliveryException clasificar(RuntimeException error, String destinatarioEnmascarado) {
		List<Throwable> senales = aplanar(error);

		// La autenticacion se decide ANTES del codigo, y no es un detalle: un rechazo de
		// credenciales llega como "535 5.7.8 authentication failed", o sea con un 5xx que la
		// regla del codigo leeria como rechazo definitivo del mensaje. No lo es: el mensaje esta
		// bien, lo que esta mal es la configuracion del relay.
		for (Throwable senal : senales) {
			if (senal instanceof AuthenticationFailedException
					|| senal instanceof MailAuthenticationException) {
				return EmailDeliveryException.transitorio(
						"El servidor SMTP rechazo las credenciales del remitente", error);
			}
		}

		Integer codigo = primerCodigoSmtp(senales);
		if (codigo != null && codigo >= 500) {
			return EmailDeliveryException.permanente(
					"El servidor SMTP rechazo el mensaje de forma definitiva (codigo " + codigo
							+ ") para " + destinatarioEnmascarado);
		}
		if (codigo != null && codigo >= 400) {
			return EmailDeliveryException.transitorio(
					"El servidor SMTP rechazo el mensaje de forma temporal (codigo " + codigo
							+ ") para " + destinatarioEnmascarado,
					error);
		}

		for (Throwable senal : senales) {
			if (senal instanceof SendFailedException fallo) {
				return clasificarSendFailed(fallo, error, destinatarioEnmascarado);
			}
		}

		for (Throwable senal : senales) {
			if (senal instanceof IOException) {
				return EmailDeliveryException.transitorio(
						"No se pudo hablar con el servidor SMTP: " + senal.getClass().getSimpleName(),
						error);
			}
			if (senal instanceof MailParseException) {
				return EmailDeliveryException.permanente(
						"El mensaje no se pudo componer y reintentarlo daria lo mismo");
			}
		}

		return EmailDeliveryException.transitorio(
				"Fallo no clasificado al entregar el mensaje: " + error.getClass().getSimpleName(),
				error);
	}

	/**
	 * Un {@code SendFailedException} sin codigo legible.
	 *
	 * <p>Que queden direcciones VALIDAS SIN ENVIAR es la firma de un corte a mitad de la
	 * conversacion: el servidor las acepto y despues algo se cayo. Eso se reintenta. Que la
	 * unica direccion figure como invalida es un rechazo del destinatario, y eso no.
	 */
	private static EmailDeliveryException clasificarSendFailed(
			SendFailedException fallo, RuntimeException error, String destinatarioEnmascarado) {
		boolean hayInvalidas = fallo.getInvalidAddresses() != null
				&& fallo.getInvalidAddresses().length > 0;
		boolean quedaronValidasSinEnviar = fallo.getValidUnsentAddresses() != null
				&& fallo.getValidUnsentAddresses().length > 0;

		if (quedaronValidasSinEnviar) {
			return EmailDeliveryException.transitorio(
					"El envio se corto con direcciones validas sin entregar", error);
		}
		if (hayInvalidas) {
			return EmailDeliveryException.permanente(
					"El servidor SMTP considero invalida la direccion de destino "
							+ destinatarioEnmascarado);
		}
		return EmailDeliveryException.transitorio(
				"El servidor SMTP no acepto el mensaje y no dijo por que", error);
	}

	/**
	 * Todas las excepciones relevantes: la de arriba, sus causas y —si es un
	 * {@code MailSendException}— las de cada mensaje fallido.
	 *
	 * <p>Sin esto no se ve nada: {@code JavaMailSenderImpl} envuelve el fallo real en un
	 * {@code MailSendException} cuyo propio mensaje es solo {@code "Failed messages: ..."}. La
	 * senal —el codigo del servidor, el {@code ConnectException}— esta un nivel mas abajo.
	 */
	private static List<Throwable> aplanar(Throwable raiz) {
		List<Throwable> resultado = new ArrayList<>();
		// ArrayDeque no acepta null, y una causa nula es el caso mas comun de todos: cada
		// excepcion hoja lo es. Se filtra al encolar.
		Deque<Throwable> pendientes = new ArrayDeque<>();
		encolar(pendientes, raiz);
		while (!pendientes.isEmpty() && resultado.size() < PROFUNDIDAD_MAXIMA_DE_CAUSAS) {
			Throwable actual = pendientes.poll();
			if (resultado.contains(actual)) {
				continue;
			}
			resultado.add(actual);
			if (actual instanceof MailSendException envio
					&& envio.getMessageExceptions() != null) {
				for (Exception anidada : envio.getMessageExceptions()) {
					encolar(pendientes, anidada);
				}
			}
			if (actual instanceof MessagingException mensajeria) {
				encolar(pendientes, mensajeria.getNextException());
			}
			encolar(pendientes, actual.getCause());
		}
		return resultado;
	}

	private static void encolar(Deque<Throwable> pendientes, Throwable candidato) {
		if (candidato != null) {
			pendientes.add(candidato);
		}
	}

	/** El primer codigo de respuesta SMTP que aparezca en la cadena, o {@code null}. */
	private static Integer primerCodigoSmtp(List<Throwable> senales) {
		for (Throwable senal : senales) {
			String mensaje = senal.getMessage();
			if (mensaje == null) {
				continue;
			}
			Matcher matcher = CODIGO_SMTP.matcher(mensaje);
			if (matcher.find()) {
				return Integer.valueOf(matcher.group(1));
			}
		}
		return null;
	}
}
