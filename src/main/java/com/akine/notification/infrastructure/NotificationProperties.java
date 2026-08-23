package com.akine.notification.infrastructure;

import com.akine.notification.domain.OutboxWorkerSettings;
import com.akine.notification.domain.RetryBackoffPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuracion del modulo {@code notification}, bajo el prefijo {@code akine.notification}.
 *
 * <p><b>Todos los valores tienen default en codigo</b>: el modulo arranca sin tocar
 * {@code application.yml}. Sobreescribir uno es una decision operativa, no un requisito para
 * que la aplicacion levante.
 *
 * <pre>
 * akine:
 *   notification:
 *     email:
 *       mode: log            # log | smtp   (default: log)
 *     worker:
 *       enabled: true        # default: true
 *       fixed-delay-ms: 15000
 *       batch-size: 20
 *       lease: 5m
 *       max-attempts: 5
 * </pre>
 *
 * <p>Ningun secreto vive aca: cuando exista el adaptador SMTP real, host, usuario y
 * contrasena entran por variable de entorno, jamas versionadas.
 */
@ConfigurationProperties(prefix = "akine.notification")
public class NotificationProperties {

	private final Email email = new Email();
	private final Worker worker = new Worker();

	public Email getEmail() {
		return email;
	}

	public Worker getWorker() {
		return worker;
	}

	/**
	 * Traduce la configuracion a un valor del dominio.
	 *
	 * <p>Existe para que {@code application} no tenga que ver esta clase: vive en
	 * {@code infrastructure} y ninguna capa puede depender de ahi.
	 */
	public OutboxWorkerSettings toWorkerSettings() {
		List<Duration> escalones = RetryBackoffPolicy.porDefecto().escalones();
		return new OutboxWorkerSettings(
				worker.getBatchSize(),
				worker.getLease(),
				new RetryBackoffPolicy(escalones, worker.getMaxAttempts()));
	}

	/** Modo del adaptador de email. */
	public enum EmailMode {

		/** Escribe el envio en el log estructurado y no manda nada. Default. */
		LOG,

		/** SMTP real. Punto de extension: ver {@code SmtpEmailSender}. */
		SMTP
	}

	public static class Email {

		private EmailMode mode = EmailMode.LOG;

		/** Remitente de los mensajes. */
		private String from = "no-reply@akine.local";

		/** Base publica desde la que se arman los enlaces; la usa quien resuelve el enlace. */
		private String baseUrl = "http://localhost:4200";

		public EmailMode getMode() {
			return mode;
		}

		public void setMode(EmailMode mode) {
			this.mode = mode;
		}

		public String getFrom() {
			return from;
		}

		public void setFrom(String from) {
			this.from = from;
		}

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}
	}

	public static class Worker {

		/** Apagarlo deja las notificaciones encoladas sin entregar; util en tests. */
		private boolean enabled = true;

		/** Espera entre el fin de un tick y el comienzo del siguiente. */
		private long fixedDelayMs = 15_000;

		/** Filas por tick. */
		private int batchSize = 20;

		/** Cuanto puede estar una fila PROCESANDO antes de considerarse huerfana. */
		private Duration lease = Duration.ofMinutes(5);

		/** Intentos antes de AGOTADA. */
		private int maxAttempts = 5;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public long getFixedDelayMs() {
			return fixedDelayMs;
		}

		public void setFixedDelayMs(long fixedDelayMs) {
			this.fixedDelayMs = fixedDelayMs;
		}

		public int getBatchSize() {
			return batchSize;
		}

		public void setBatchSize(int batchSize) {
			this.batchSize = batchSize;
		}

		public Duration getLease() {
			return lease;
		}

		public void setLease(Duration lease) {
			this.lease = lease;
		}

		public int getMaxAttempts() {
			return maxAttempts;
		}

		public void setMaxAttempts(int maxAttempts) {
			this.maxAttempts = maxAttempts;
		}
	}
}
