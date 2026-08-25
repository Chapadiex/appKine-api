package com.akine.notification.infrastructure;

import com.akine.notification.domain.OutboxWorkerSettings;
import com.akine.notification.domain.port.EmailSender;
import com.akine.notification.domain.port.JitterSource;
import com.akine.notification.domain.port.NotificationClock;
import com.akine.notification.spi.NotificationType;
import com.akine.notification.spi.SecureLinkResolver;
import com.akine.platform.spi.config.PerfilesDeEjecucion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cableado del modulo {@code notification}.
 *
 * <p>Es el unico lugar del modulo que conoce implementaciones concretas: todo lo demas depende
 * de puertos. Cambiar de adaptador de email es cambiar una linea aca.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfig {

	private static final Logger log = LoggerFactory.getLogger(NotificationConfig.class);

	/** Reloj real. Los tests inyectan uno fijo y prueban el backoff sin dormir. */
	@Bean
	public NotificationClock notificationClock() {
		return Instant::now;
	}

	/**
	 * Jitter real. Los tests inyectan un valor fijo y afirman la espera exacta de cada intento.
	 *
	 * <p>{@code ThreadLocalRandom} y no {@code Random}: el worker corre en el pool del
	 * scheduler y una instancia compartida de {@code Random} serializa a todos sus hilos sobre
	 * el mismo estado atomico. Aca no hace falta calidad criptografica —es la dispersion de una
	 * espera, no un token—, asi que la version por hilo es la correcta.
	 */
	@Bean
	public JitterSource jitterSource() {
		return () -> ThreadLocalRandom.current().nextDouble();
	}

	@Bean
	public OutboxWorkerSettings outboxWorkerSettings(NotificationProperties properties) {
		return properties.toWorkerSettings();
	}

	/**
	 * Remitente de desarrollo. Es publico y esta versionado: {@code .local} no es un dominio
	 * enrutable, asi que un relay real lo rechaza. Fuera de un perfil de desarrollo, presentarlo
	 * impide el arranque, igual que el secreto de firma de desarrollo en {@code JwtEmitter}.
	 */
	static final String REMITENTE_DE_DESARROLLO = "no-reply@akine.local";

	/**
	 * Adaptador de email activo.
	 *
	 * <h2>Por que el modo tambien se valida contra el perfil</h2>
	 *
	 * <p>{@code log} es el default y sirve para ejercitar el circuito completo —encolar,
	 * reclamar, entregar, marcar ENVIADA— sin un servidor de correo. Fuera de un perfil de
	 * desarrollo <b>no se acepta</b>, y el arranque falla. La razon no es purismo: ADR-0018
	 * apoya todas sus respuestas uniformes en que la persona legitima reciba el correo y siga
	 * por ahi. Un despliegue en modo {@code log} responde {@code 202} a cada activacion y a cada
	 * recuperacion, escribe una linea en el log y tira el mensaje. Nadie se entera: no hay error,
	 * no hay fila FALLIDA, no hay nada que mirar. Un arranque fallido con este mensaje es
	 * infinitamente mas barato.
	 *
	 * <h2>Lo que se exige en modo smtp</h2>
	 *
	 * <ul>
	 *   <li><b>Host</b>, siempre. Sin default (ADR-0017, misma regla que el secreto del JWT).</li>
	 *   <li><b>Cifrado del transporte</b>, fuera de desarrollo. {@code NINGUNA} manda la
	 *       contrasena del relay en claro por la red; solo tiene sentido contra un contenedor de
	 *       prueba en la misma maquina.</li>
	 *   <li><b>Un remitente propio</b>, fuera de desarrollo. El de desarrollo esta versionado.</li>
	 * </ul>
	 *
	 * @param environment se usa solo para saber si hay un perfil de desarrollo activo
	 */
	@Bean
	public EmailSender emailSender(NotificationProperties properties, Environment environment) {
		boolean desarrollo = PerfilesDeEjecucion.esDesarrollo(environment);
		NotificationProperties.Email email = properties.getEmail();

		if (email.getMode() != NotificationProperties.EmailMode.SMTP) {
			if (!desarrollo) {
				throw new IllegalStateException(
						"El adaptador de email esta en modo 'log', que registra los mensajes y NO "
								+ "envia ninguno. Fuera de los perfiles de desarrollo "
								+ PerfilesDeEjecucion.perfilesDeDesarrollo() + " eso deja sin "
								+ "activacion ni recuperacion a toda persona que se registre. "
								+ "Configurar akine.notification.email.mode=smtp y el bloque "
								+ "akine.notification.email.smtp.");
			}
			log.info("Adaptador de email en modo log: los mensajes se registran y no se envian");
			return new LogEmailSender(email.getFrom());
		}

		NotificationProperties.Smtp smtp = email.getSmtp();
		if (smtp.getHost() == null || smtp.getHost().isBlank()) {
			// SIN HOST NO SE ARRANCA. Un default comodo aca es un despliegue que apunta a
			// localhost y descarta cada correo sin que nadie lo note.
			throw new IllegalStateException(
					"No hay servidor SMTP configurado. Definir AKINE_MAIL_HOST (propiedad "
							+ "akine.notification.email.smtp.host). No hay valor por defecto: en "
							+ "los perfiles de desarrollo " + PerfilesDeEjecucion.perfilesDeDesarrollo()
							+ " lo provee application-local.yml apuntando al Mailpit del compose.");
		}
		if (!desarrollo
				&& smtp.getTransportSecurity() == NotificationProperties.TransportSecurity.NINGUNA) {
			throw new IllegalStateException(
					"akine.notification.email.smtp.transport-security=NINGUNA manda la contrasena "
							+ "del relay en claro por la red. Solo se acepta en los perfiles de "
							+ "desarrollo " + PerfilesDeEjecucion.perfilesDeDesarrollo()
							+ ". Usar STARTTLS o SSL.");
		}
		if (!desarrollo && REMITENTE_DE_DESARROLLO.equals(email.getFrom())) {
			throw new IllegalStateException(
					"El remitente " + REMITENTE_DE_DESARROLLO + " es el de desarrollo, esta "
							+ "versionado y su dominio no es enrutable. Definir AKINE_MAIL_FROM "
							+ "(propiedad akine.notification.email.from).");
		}

		log.info("Adaptador de email en modo smtp: relay {}:{} transporte={} autenticado={}",
				smtp.getHost(), smtp.getPort(), smtp.getTransportSecurity(),
				!smtp.getUsername().isBlank());
		return new SmtpEmailSender(javaMailSender(smtp), email.getFrom());
	}

	/**
	 * El cliente SMTP, armado a mano desde las propiedades del modulo.
	 *
	 * <p><b>Por que no se usa la autoconfiguracion de {@code spring.mail.*}.</b> Esa
	 * autoconfiguracion crea el bean solo si la propiedad existe y, si no existe, no crea nada:
	 * el fallo aparece mas tarde, como un bean faltante o —peor— como un envio que nunca ocurre.
	 * Con las propiedades bajo {@code akine.notification.email.smtp} la validacion de arranque
	 * vive en un solo lugar, junto a la regla de perfiles, y el mensaje de error dice que
	 * variable de entorno falta.
	 */
	private static JavaMailSender javaMailSender(NotificationProperties.Smtp smtp) {
		JavaMailSenderImpl sender = new JavaMailSenderImpl();
		sender.setHost(smtp.getHost());
		sender.setPort(smtp.getPort());
		sender.setUsername(smtp.getUsername());
		sender.setPassword(smtp.getPassword());
		sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

		long timeoutMs = smtp.getTimeout().toMillis();
		Properties javaMail = sender.getJavaMailProperties();
		javaMail.put("mail.transport.protocol", "smtp");
		javaMail.put("mail.smtp.auth", String.valueOf(!smtp.getUsername().isBlank()));
		javaMail.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMs));
		javaMail.put("mail.smtp.timeout", String.valueOf(timeoutMs));
		javaMail.put("mail.smtp.writetimeout", String.valueOf(timeoutMs));

		switch (smtp.getTransportSecurity()) {
			case STARTTLS -> {
				javaMail.put("mail.smtp.starttls.enable", "true");
				// required=true, no solo enable: con enable a secas, un servidor que no ofrece
				// STARTTLS hace que el cliente siga en claro sin avisar. Eso es exactamente el
				// downgrade que el cifrado deberia impedir.
				javaMail.put("mail.smtp.starttls.required", "true");
			}
			case SSL -> {
				javaMail.put("mail.smtp.ssl.enable", "true");
				javaMail.put("mail.smtp.ssl.checkserveridentity", "true");
			}
			case NINGUNA -> {
				javaMail.put("mail.smtp.starttls.enable", "false");
				javaMail.put("mail.smtp.ssl.enable", "false");
			}
		}
		return sender;
	}

	/**
	 * Resolutor de enlaces de reserva, activo mientras ningun modulo registre el suyo.
	 *
	 * <p>El dueño de los tokens —{@code identity}— es quien debe implementar
	 * {@link SecureLinkResolver}. Hasta que exista ese bean, este devuelve
	 * {@link Optional#empty()}, con lo cual toda notificacion que necesite enlace queda FALLIDA
	 * con motivo explicito en vez de enviarse rota.
	 *
	 * <p><b>Por que un fallback y no un arranque fallido:</b> el modulo {@code notification} no
	 * puede exigir que exista {@code identity} para levantar —seria la dependencia invertida
	 * que este puerto justamente evita—, y las notificaciones sin enlace
	 * ({@code CUENTA_YA_REGISTRADA}) funcionan igual. El {@code warn} de arranque deja el
	 * hueco visible.
	 */
	@Bean
	@ConditionalOnMissingBean(SecureLinkResolver.class)
	public SecureLinkResolver secureLinkResolverNoDisponible() {
		log.warn("Ningun modulo registro un SecureLinkResolver: las notificaciones con enlace "
				+ "seguro quedaran FALLIDAS. Lo implementa el modulo dueño de los tokens.");
		return new SecureLinkResolver() {
			@Override
			public Optional<String> resolveLink(
					NotificationType tipo, String referenciaTokenId) {
				return Optional.empty();
			}

			@Override
			public void consumeLink(
					NotificationType tipo, String referenciaTokenId) {
				// No hay nada que soltar: este resolutor nunca entrego un enlace.
			}
		};
	}
}
