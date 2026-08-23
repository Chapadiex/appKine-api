package com.akine.platform.infrastructure.security;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.akine.platform.spi.security.AccessTokenVerifier;

/**
 * Cableado de las piezas de autenticacion de la cadena.
 *
 * <p>Vive separado de {@code SecurityConfig} por el mismo criterio que {@code TenantConfig}: esa
 * clase <b>arma la cadena</b>, esta <b>construye lo que se le inserta</b>. Tenerlas juntas
 * obligaria a los slices que solo quieren la cadena a arrastrar tambien la construccion de los
 * filtros.
 *
 * <p><b>Por que los filtros se declaran aca y no con {@code @Component}.</b> Spring Boot
 * registra automaticamente en el contenedor de servlets cualquier bean de tipo {@code Filter}.
 * Un filtro de autenticacion registrado dos veces —una en la cadena de Spring Security y otra
 * suelta antes de ella— procesa el mismo request con dos criterios distintos, que es como se
 * cuelan los agujeros. Por eso cada uno lleva su {@link FilterRegistrationBean} desactivado,
 * igual que {@code TenantContextFilter}.
 */
@Configuration
@EnableConfigurationProperties(SecurityFiltersConfig.RateLimitProperties.class)
public class SecurityFiltersConfig {

	/**
	 * El filtro que publica el principal a partir del bearer.
	 *
	 * <p>Recibe el {@link AccessTokenVerifier} por el {@code spi} de {@code platform}: quien lo
	 * implementa hoy es {@code identity.infrastructure.JwtEmitter}, y {@code platform} no lo
	 * sabe ni debe saberlo (ADR-0001).
	 */
	@Bean
	public JwtAuthenticationFilter jwtAuthenticationFilter(AccessTokenVerifier verifier) {
		return new JwtAuthenticationFilter(verifier);
	}

	@Bean
	public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
			JwtAuthenticationFilter filtro) {
		FilterRegistrationBean<JwtAuthenticationFilter> registro =
				new FilterRegistrationBean<>(filtro);
		registro.setEnabled(false);
		return registro;
	}

	@Bean
	public RateLimitFilter rateLimitFilter(RateLimitProperties properties) {
		return new RateLimitFilter(
				new FixedWindowRateLimiter(properties.getWindow(), properties.getMaxAttempts()),
				new FixedWindowRateLimiter(
						properties.getWindow(), properties.getRegisterMaxAttempts()),
				Clock.systemUTC(),
				properties.isEnabled());
	}

	@Bean
	public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
			RateLimitFilter filtro) {
		FilterRegistrationBean<RateLimitFilter> registro = new FilterRegistrationBean<>(filtro);
		registro.setEnabled(false);
		return registro;
	}

	/**
	 * Limite de intentos sobre los endpoints sensibles de identidad, bajo
	 * {@code akine.security.rate-limit}.
	 *
	 * <pre>
	 * akine:
	 *   security:
	 *     rate-limit:
	 *       enabled: true
	 *       window: 1m
	 *       max-attempts: 30
	 *       register-max-attempts: 5
	 * </pre>
	 *
	 * <p>Los valores por defecto son holgados a proposito. El limite existe para frenar la
	 * automatizacion, no para castigar a quien se equivoca dos veces escribiendo su contrasena:
	 * como la clave es {@code ruta + IP} y nunca el email (ver {@link RateLimitFilter}), un
	 * consultorio entero detras del mismo NAT comparte cupo.
	 */
	@ConfigurationProperties(prefix = "akine.security.rate-limit")
	public static class RateLimitProperties {

		/**
		 * Interruptor.
		 *
		 * <p>Existe para poder apagarlo en un entorno de prueba de carga, no para apagarlo en
		 * produccion: sin limite, el login queda expuesto a fuerza bruta y a agotamiento de CPU
		 * por Argon2id.
		 */
		private boolean enabled = true;

		/** Largo de la ventana de conteo. */
		private Duration window = Duration.ofMinutes(1);

		/** Intentos permitidos por ventana y por clave. */
		private int maxAttempts = 30;

		/**
		 * Intentos de alta self-service permitidos por ventana y por clave.
		 *
		 * <p>Mucho mas bajo que el general y a proposito: registrarse es un acto que una persona
		 * hace una vez, mientras que un alta escribe cinco filas en cinco tablas y dispara un
		 * correo al buzon de un tercero. Ver {@code RateLimitFilter.RUTA_DE_REGISTRO}.
		 */
		private int registerMaxAttempts = 5;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public Duration getWindow() {
			return window;
		}

		public void setWindow(Duration window) {
			this.window = window;
		}

		public int getMaxAttempts() {
			return maxAttempts;
		}

		public void setMaxAttempts(int maxAttempts) {
			this.maxAttempts = maxAttempts;
		}

		public int getRegisterMaxAttempts() {
			return registerMaxAttempts;
		}

		public void setRegisterMaxAttempts(int registerMaxAttempts) {
			this.registerMaxAttempts = registerMaxAttempts;
		}
	}
}
