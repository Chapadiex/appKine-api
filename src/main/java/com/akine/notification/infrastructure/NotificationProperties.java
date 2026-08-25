package com.akine.notification.infrastructure;

import com.akine.notification.domain.OutboxWorkerSettings;
import com.akine.notification.domain.RetryBackoffPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuracion del modulo {@code notification}, bajo el prefijo {@code akine.notification}.
 *
 * <p><b>Casi todos los valores tienen default en codigo</b>: el modulo arranca sin tocar
 * {@code application.yml}. Sobreescribir uno es una decision operativa, no un requisito para
 * que la aplicacion levante. La excepcion es el bloque {@code smtp}, que no tiene default
 * fuera de los perfiles de desarrollo: ver {@link NotificationConfig}.
 *
 * <pre>
 * akine:
 *   notification:
 *     email:
 *       mode: log            # log | smtp   (default: log; fuera de desarrollo hay que elegir smtp)
 *       from: no-reply@akine.local
 *       base-url: http://localhost:4200
 *       smtp:
 *         host: ${AKINE_MAIL_HOST:}        # SIN default: sin esto no se arranca en modo smtp
 *         port: ${AKINE_MAIL_PORT:587}
 *         username: ${AKINE_MAIL_USER:}
 *         password: ${AKINE_MAIL_PASSWORD:}
 *         transport-security: starttls     # starttls | ssl | ninguna
 *         timeout: 10s
 *     worker:
 *       enabled: true        # default: true
 *       fixed-delay-ms: 15000
 *       batch-size: 20
 *       lease: 5m
 *       max-attempts: 5
 * </pre>
 *
 * <p><b>Ningun secreto vive aca.</b> Host, usuario y contrasena entran por variable de entorno
 * y jamas se versionan: los campos de abajo son el lugar donde ATERRIZAN esas variables, no
 * donde se escriben sus valores. El unico valor literal que existe en el repositorio es el del
 * perfil {@code local}, que apunta al Mailpit del {@code compose.yaml} y no tiene credenciales.
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

	/**
	 * Como se protege la conexion con el relay.
	 *
	 * <p>Se declara explicitamente en vez de deducirse del puerto: "es 465, entonces sera SSL"
	 * es la clase de suposicion que termina mandando una contrasena en claro cuando alguien
	 * cambia el puerto.
	 */
	public enum TransportSecurity {

		/** Conexion en claro que se promueve a TLS con {@code STARTTLS}. Lo habitual (587). */
		STARTTLS,

		/** TLS desde el primer byte, sin negociacion previa (465). */
		SSL,

		/**
		 * Sin cifrado. Solo tiene sentido contra un servidor de prueba en la misma maquina;
		 * fuera de un perfil de desarrollo, {@link NotificationConfig} no lo acepta.
		 */
		NINGUNA
	}

	public static class Email {

		private EmailMode mode = EmailMode.LOG;

		private final Smtp smtp = new Smtp();

		public Smtp getSmtp() {
			return smtp;
		}

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

	/**
	 * Conexion con el relay SMTP. Todo esto entra por variable de entorno.
	 *
	 * <p>{@code host} arranca vacio a proposito y NO tiene default: es la misma regla que
	 * ADR-0017 aplica al secreto de firma del JWT. Un default comodo aca significaria que un
	 * despliegue al que se le olvido la variable levanta apuntando a un servidor que no es el
	 * suyo, o a {@code localhost}, y descarta en silencio cada activacion y cada recuperacion.
	 * Sin host, en modo {@code smtp}, la aplicacion no arranca.
	 */
	public static class Smtp {

		/** Host del relay. Sin default: ver el javadoc de la clase. */
		private String host = "";

		/** 587 con STARTTLS es lo estandar hoy; 465 con SSL implicito es el legado. */
		private int port = 587;

		/** Vacio significa relay sin autenticacion, como el Mailpit de desarrollo. */
		private String username = "";

		/** Nunca versionada. Llega por {@code AKINE_MAIL_PASSWORD}. */
		private String password = "";

		private TransportSecurity transportSecurity = TransportSecurity.STARTTLS;

		/**
		 * Tope para conectar, leer y escribir.
		 *
		 * <p>Sin esto el default de JavaMail es "esperar para siempre": un relay que acepta la
		 * conexion TCP y despues no contesta deja el hilo del worker colgado, el lote entero sin
		 * procesar y las filas en PROCESANDO hasta que vence el lease. Un timeout corto convierte
		 * ese cuelgue en un fallo transitorio, que es lo que el outbox sabe manejar.
		 */
		private Duration timeout = Duration.ofSeconds(10);

		public String getHost() {
			return host;
		}

		public void setHost(String host) {
			this.host = host;
		}

		public int getPort() {
			return port;
		}

		public void setPort(int port) {
			this.port = port;
		}

		public String getUsername() {
			return username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		public TransportSecurity getTransportSecurity() {
			return transportSecurity;
		}

		public void setTransportSecurity(TransportSecurity transportSecurity) {
			this.transportSecurity = transportSecurity;
		}

		public Duration getTimeout() {
			return timeout;
		}

		public void setTimeout(Duration timeout) {
			this.timeout = timeout;
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
