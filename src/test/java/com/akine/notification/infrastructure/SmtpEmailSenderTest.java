package com.akine.notification.infrastructure;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import com.akine.notification.domain.EmailMessage;
import com.akine.notification.domain.EmailTemplates;
import com.akine.notification.domain.SanitizedPayload;
import com.akine.notification.domain.exception.EmailDeliveryException;
import com.akine.notification.spi.NotificationType;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El adaptador SMTP, probado contra un servidor SMTP de verdad.
 *
 * <h2>Por que hay un servidor y no un mock</h2>
 *
 * <p>Un test que verifica que {@code send(..)} no lanza excepcion no prueba absolutamente nada:
 * eso ya lo hacia {@code LogEmailSender}, que registra el mensaje y lo tira. Lo que hay que
 * demostrar es que <b>del otro lado aparece un correo</b>, con el remitente, el destinatario, el
 * asunto y —lo unico que de verdad importa— el enlace de un solo uso dentro del cuerpo. GreenMail
 * levanta un SMTP en el mismo proceso, asi que el test es rapido, hermetico y no necesita Docker.
 *
 * <h2>Y por que la clasificacion se prueba aparte</h2>
 *
 * <p>Transitorio contra permanente es la unica decision del adaptador, y de ella depende que el
 * outbox reintente o agote. Los dos casos que se pueden provocar de verdad —el servidor caido y
 * la direccion que no es una direccion— se prueban contra el servidor; los codigos de respuesta
 * del relay no se pueden provocar con GreenMail, asi que se alimenta al clasificador con las
 * excepciones exactas que JavaMail produce en cada caso.
 */
class SmtpEmailSenderTest {

	private static final String REMITENTE = "no-reply@ejemplo.test";
	private static final String DESTINATARIO = "paula.gomez@ejemplo.test";
	private static final String ENLACE = "https://akine.ejemplo.test/activar?t=ENLACE-DE-UN-SOLO-USO";

	@RegisterExtension
	static final GreenMailExtension SMTP =
			new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort())
					.withPerMethodLifecycle(true);

	private static EmailMessage mensajeDeActivacion() {
		return EmailTemplates.render(
				NotificationType.ACTIVACION_CUENTA,
				DESTINATARIO,
				SanitizedPayload.of(Map.of("nombre", "Paula")),
				ENLACE);
	}

	private static SmtpEmailSender adaptadorContra(String host, int puerto) {
		JavaMailSenderImpl sender = new JavaMailSenderImpl();
		sender.setHost(host);
		sender.setPort(puerto);
		sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
		sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "3000");
		sender.getJavaMailProperties().put("mail.smtp.timeout", "3000");
		return new SmtpEmailSender(sender, REMITENTE);
	}

	/**
	 * El cuerpo tal como lo lee un cliente de correo.
	 *
	 * <p>No se mira el texto crudo: viaja en quoted-printable, asi que una tilde aparece como
	 * {@code =C3=B3} y una linea larga queda partida por un {@code =} al final. Afirmar sobre eso
	 * probaria la codificacion del transporte, no el contenido. {@code getContent()} deshace las
	 * dos cosas, que es exactamente lo que hace el destinatario real.
	 */
	private static String cuerpoDe(MimeMessage recibido) throws Exception {
		return String.valueOf(recibido.getContent());
	}

	// =================================================================================
	// Lo que importa: el correo llega
	// =================================================================================

	@Test
	@DisplayName("el correo llega al servidor SMTP con remitente, destinatario, asunto y el enlace")
	void el_correo_llega_de_verdad() throws Exception {
		SmtpEmailSender adaptador = adaptadorContra("127.0.0.1", SMTP.getSmtp().getPort());

		adaptador.send(mensajeDeActivacion());

		assertThat(SMTP.waitForIncomingEmail(5_000, 1)).isTrue();
		MimeMessage[] recibidos = SMTP.getReceivedMessages();
		assertThat(recibidos).hasSize(1);

		MimeMessage recibido = recibidos[0];
		assertThat(InternetAddress.toString(recibido.getFrom())).isEqualTo(REMITENTE);
		assertThat(InternetAddress.toString(recibido.getAllRecipients())).isEqualTo(DESTINATARIO);
		assertThat(recibido.getSubject()).isEqualTo("Activa tu cuenta de AKINE");
		// El enlace de un solo uso es la unica razon por la que este correo existe. Si llega
		// todo menos el enlace, el canal esta igual de roto que si no llegara nada.
		assertThat(cuerpoDe(recibido)).contains(ENLACE).contains("Hola Paula");
	}

	@Test
	@DisplayName("el asunto y el cuerpo viajan en UTF-8: las tildes no llegan rotas")
	void el_cuerpo_viaja_en_utf8() throws Exception {
		SmtpEmailSender adaptador = adaptadorContra("127.0.0.1", SMTP.getSmtp().getPort());

		adaptador.send(new EmailMessage(DESTINATARIO, "Activá tu cuenta", "Sesión de kinesiología"));

		assertThat(SMTP.waitForIncomingEmail(5_000, 1)).isTrue();
		MimeMessage recibido = SMTP.getReceivedMessages()[0];
		assertThat(recibido.getSubject()).isEqualTo("Activá tu cuenta");
		assertThat(cuerpoDe(recibido)).contains("Sesión de kinesiología");
	}

	// =================================================================================
	// Transitorio contra permanente, contra el servidor real
	// =================================================================================

	@Test
	@DisplayName("un servidor que no esta escuchando es TRANSITORIO: el outbox tiene que reintentar")
	void un_servidor_caido_es_transitorio() throws IOException {
		int puertoCerrado;
		try (ServerSocket libre = new ServerSocket(0)) {
			puertoCerrado = libre.getLocalPort();
		}
		SmtpEmailSender adaptador = adaptadorContra("127.0.0.1", puertoCerrado);

		assertThatThrownBy(() -> adaptador.send(mensajeDeActivacion()))
				.isInstanceOf(EmailDeliveryException.class)
				.satisfies(e -> assertThat(((EmailDeliveryException) e).esTransitorio())
						.as("un relay caido vuelve; agotar el correo por eso lo pierde para siempre")
						.isTrue());
	}

	@Test
	@DisplayName("una direccion que no es una direccion es PERMANENTE y ni siquiera abre la conexion")
	void una_direccion_invalida_es_permanente() {
		SmtpEmailSender adaptador = adaptadorContra("127.0.0.1", SMTP.getSmtp().getPort());
		EmailMessage roto = new EmailMessage("esto-no-es-un-email", "Asunto", "Cuerpo");

		assertThatThrownBy(() -> adaptador.send(roto))
				.isInstanceOf(EmailDeliveryException.class)
				.satisfies(e -> assertThat(((EmailDeliveryException) e).esTransitorio())
						.as("reintentar un texto que nunca va a ser una direccion gasta cuota y nada mas")
						.isFalse());

		assertThat(SMTP.getReceivedMessages())
				.as("el rechazo es local: no se abre conexion ni se gasta una linea de cuota")
				.isEmpty();
	}

	@Test
	@DisplayName("el fallo no lleva el enlace ni el cuerpo en su mensaje (RN-M02-003, T-11)")
	void el_fallo_no_filtra_el_enlace() throws IOException {
		int puertoCerrado;
		try (ServerSocket libre = new ServerSocket(0)) {
			puertoCerrado = libre.getLocalPort();
		}
		SmtpEmailSender adaptador = adaptadorContra("127.0.0.1", puertoCerrado);
		EmailMessage mensaje = mensajeDeActivacion();

		assertThatThrownBy(() -> adaptador.send(mensaje))
				.satisfies(e -> {
					// El mensaje de la excepcion termina —sanitizado, pero termina— en la columna
					// error_sanitizado, que se lee desde una pantalla y viaja a los backups.
					assertThat(e.getMessage()).doesNotContain(ENLACE);
					assertThat(e.getMessage()).doesNotContain(mensaje.cuerpo());
					assertThat(e.getMessage()).doesNotContain(DESTINATARIO);
				});
	}

	// =================================================================================
	// Los codigos del relay, que GreenMail no sabe producir
	// =================================================================================

	@Nested
	@DisplayName("clasificacion de las respuestas del relay")
	class Clasificacion {

		private static final String ENMASCARADO = "p***@ejemplo.test";

		/** Lo que arma {@code JavaMailSenderImpl} cuando el envio de un mensaje falla. */
		private MailSendException envioFallido(Exception causa) {
			Map<Object, Exception> fallidos = new LinkedHashMap<>();
			fallidos.put("mensaje-1", causa);
			return new MailSendException(fallidos);
		}

		@Test
		@DisplayName("5xx es PERMANENTE: el relay rechazo definitivamente")
		void cinco_xx_es_permanente() {
			EmailDeliveryException resultado = SmtpEmailSender.clasificar(
					envioFallido(new jakarta.mail.SendFailedException(
							"550 5.1.1 <paula@ejemplo.test>: Recipient address rejected")),
					ENMASCARADO);

			assertThat(resultado.esTransitorio()).isFalse();
			assertThat(resultado.getMessage()).contains("550");
		}

		@Test
		@DisplayName("4xx es TRANSITORIO: el relay pidio volver mas tarde")
		void cuatro_xx_es_transitorio() {
			EmailDeliveryException resultado = SmtpEmailSender.clasificar(
					envioFallido(new jakarta.mail.SendFailedException(
							"451 4.3.0 Temporary server error, please try again later")),
					ENMASCARADO);

			assertThat(resultado.esTransitorio()).isTrue();
			assertThat(resultado.getMessage()).contains("451");
		}

		@Test
		@DisplayName("una direccion marcada invalida por el relay, sin codigo legible, es PERMANENTE")
		void direccion_invalida_sin_codigo_es_permanente() throws Exception {
			jakarta.mail.SendFailedException fallo = new jakarta.mail.SendFailedException(
					"Invalid Addresses",
					new jakarta.mail.MessagingException("unknown user"),
					new jakarta.mail.Address[0],
					new jakarta.mail.Address[0],
					new jakarta.mail.Address[] {new InternetAddress("paula@ejemplo.test")});

			assertThat(SmtpEmailSender.clasificar(envioFallido(fallo), ENMASCARADO).esTransitorio())
					.isFalse();
		}

		@Test
		@DisplayName("un corte con direcciones validas sin entregar es TRANSITORIO")
		void corte_con_validas_sin_entregar_es_transitorio() throws Exception {
			jakarta.mail.SendFailedException fallo = new jakarta.mail.SendFailedException(
					"Connection dropped",
					new jakarta.mail.MessagingException("connection reset"),
					new jakarta.mail.Address[0],
					new jakarta.mail.Address[] {new InternetAddress("paula@ejemplo.test")},
					new jakarta.mail.Address[0]);

			assertThat(SmtpEmailSender.clasificar(envioFallido(fallo), ENMASCARADO).esTransitorio())
					.isTrue();
		}

		@Test
		@DisplayName("credenciales rechazadas es TRANSITORIO: la cola llega a AGOTADA, que es la senal")
		void credenciales_rechazadas_es_transitorio() {
			// Decision deliberada, documentada en el javadoc del adaptador: marcarlo permanente
			// perderia con estado FALLIDA cada correo emitido durante el incidente, y FALLIDA
			// significa "no hay nada que revisar".
			assertThat(SmtpEmailSender
					.clasificar(new MailAuthenticationException("535 auth failed"), ENMASCARADO)
					.esTransitorio())
					.isTrue();
		}

		@Test
		@DisplayName("un corte de red es TRANSITORIO")
		void corte_de_red_es_transitorio() {
			assertThat(SmtpEmailSender
					.clasificar(envioFallido(new jakarta.mail.MessagingException(
							"Couldn't connect to host", new ConnectException("Connection refused"))),
							ENMASCARADO)
					.esTransitorio())
					.isTrue();
		}

		@Test
		@DisplayName("un mensaje que no se puede componer es PERMANENTE")
		void mensaje_mal_compuesto_es_permanente() {
			assertThat(SmtpEmailSender
					.clasificar(new MailParseException("no se pudo parsear"), ENMASCARADO)
					.esTransitorio())
					.isFalse();
		}

		@Test
		@DisplayName("lo inesperado es TRANSITORIO: descartar por las dudas es peor que reintentar")
		void lo_inesperado_es_transitorio() {
			assertThat(SmtpEmailSender
					.clasificar(new IllegalStateException("algo que nadie previo"), ENMASCARADO)
					.esTransitorio())
					.isTrue();
		}
	}
}
