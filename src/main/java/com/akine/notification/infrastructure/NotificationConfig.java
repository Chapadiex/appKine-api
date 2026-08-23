package com.akine.notification.infrastructure;

import com.akine.notification.domain.OutboxWorkerSettings;
import com.akine.notification.domain.port.EmailSender;
import com.akine.notification.domain.port.JitterSource;
import com.akine.notification.domain.port.NotificationClock;
import com.akine.notification.spi.SecureLinkResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Instant;
import java.util.Optional;
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
	 * Adaptador de email activo.
	 *
	 * <p>{@code log} por defecto: el modulo tiene que poder arrancar y ejercitar el circuito
	 * completo sin un servidor de correo. {@code smtp} es el punto de extension y hoy falla al
	 * construirse a proposito (ver {@link SmtpEmailSender}).
	 */
	@Bean
	public EmailSender emailSender(NotificationProperties properties) {
		if (properties.getEmail().getMode() == NotificationProperties.EmailMode.SMTP) {
			return new SmtpEmailSender();
		}
		log.info("Adaptador de email en modo log: los mensajes se registran y no se envian");
		return new LogEmailSender(properties.getEmail().getFrom());
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
		return (tipo, referenciaTokenId) -> Optional.empty();
	}
}
