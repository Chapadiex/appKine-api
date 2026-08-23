package com.akine.identity.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.akine.identity.domain.SessionSettings;

import java.time.Duration;

/**
 * Configuracion del modulo {@code identity}, bajo el prefijo {@code akine.identity}.
 *
 * <p><b>Todos los valores tienen default en codigo</b>: el modulo arranca sin tocar
 * {@code application.yml}, igual que {@code notification}. Sobreescribir uno es una decision
 * operativa, no un requisito para que la aplicacion levante.
 *
 * <pre>
 * akine:
 *   identity:
 *     links:
 *       base-url: https://app.akine.com.ar
 *       activation-path: /activar
 *       reset-path: /restablecer
 *       token-param: token
 *       ttl: 10m
 *     hashing:
 *       memory-kb: 19456
 *       iterations: 2
 *       parallelism: 1
 *     session:
 *       refresh-ttl: 12h
 * </pre>
 *
 * <p>Ningun secreto vive aca. El secreto de firma del access token entra por variable de
 * entorno cuando exista el emisor, jamas versionado.
 */
@ConfigurationProperties(prefix = "akine.identity")
public class IdentityProperties {

	private final Links links = new Links();
	private final Hashing hashing = new Hashing();
	private final Session session = new Session();

	public Links getLinks() {
		return links;
	}

	public Hashing getHashing() {
		return hashing;
	}

	public Session getSession() {
		return session;
	}

	/**
	 * Vigencia de la sesion de refresh (ADR-0017).
	 *
	 * <p>El TTL del access token NO vive aca: lo administra {@code JwtEmitter.JwtProperties}
	 * bajo {@code akine.security.jwt}, junto al secreto de firma. Duplicarlo dejaria dos fuentes
	 * de verdad para el mismo numero.
	 */
	public static class Session {

		/**
		 * Vigencia ABSOLUTA del refresh desde el login. La rotacion no la extiende.
		 *
		 * <p>Subirla convierte el robo de una cookie en una persistencia mas larga; bajarla
		 * corta la jornada de trabajo antes. Doce horas es la jornada de un consultorio.
		 */
		private Duration refreshTtl = SessionSettings.REFRESH_TTL_POR_DEFECTO;

		public Duration getRefreshTtl() {
			return refreshTtl;
		}

		public void setRefreshTtl(Duration refreshTtl) {
			this.refreshTtl = refreshTtl;
		}
	}

	/** Base publica y rutas del frontend a las que apuntan los enlaces de correo. */
	public static class Links {

		/** Origen publico del frontend. Sin barra final: se normaliza igual si la trae. */
		private String baseUrl = "http://localhost:4200";

		/** Pantalla que consume un token de ACTIVACION. */
		private String activationPath = "/activar";

		/** Pantalla que consume un token de RESET. */
		private String resetPath = "/restablecer";

		/** Nombre del parametro de query que transporta el token. */
		private String tokenParam = "token";

		/**
		 * Cuanto sobrevive en memoria un enlace ya construido, esperando a que el worker lo
		 * envie. Pasado ese plazo se descarta y la notificacion queda FALLIDA con motivo: es
		 * preferible a retener una credencial en el heap indefinidamente.
		 */
		private Duration ttl = Duration.ofMinutes(10);

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getActivationPath() {
			return activationPath;
		}

		public void setActivationPath(String activationPath) {
			this.activationPath = activationPath;
		}

		public String getResetPath() {
			return resetPath;
		}

		public void setResetPath(String resetPath) {
			this.resetPath = resetPath;
		}

		public String getTokenParam() {
			return tokenParam;
		}

		public void setTokenParam(String tokenParam) {
			this.tokenParam = tokenParam;
		}

		public Duration getTtl() {
			return ttl;
		}

		public void setTtl(Duration ttl) {
			this.ttl = ttl;
		}
	}

	/**
	 * Parametros de costo del hasheo (minimos OWASP 2024, diseño §6.2).
	 *
	 * <p>Son configurables para poder subirlos cuando el hardware lo permita: el formato PHC
	 * embebe los parametros dentro de cada hash, asi que subirlos no invalida los ya
	 * guardados. Bajarlos es una decision de seguridad, no de rendimiento.
	 */
	public static class Hashing {

		/** Memoria por hasheo, en KiB. 19456 KiB = 19 MiB, minimo OWASP para Argon2id. */
		private int memoryKb = 19_456;

		/** Iteraciones. */
		private int iterations = 2;

		/** Grado de paralelismo. */
		private int parallelism = 1;

		public int getMemoryKb() {
			return memoryKb;
		}

		public void setMemoryKb(int memoryKb) {
			this.memoryKb = memoryKb;
		}

		public int getIterations() {
			return iterations;
		}

		public void setIterations(int iterations) {
			this.iterations = iterations;
		}

		public int getParallelism() {
			return parallelism;
		}

		public void setParallelism(int parallelism) {
			this.parallelism = parallelism;
		}
	}
}
