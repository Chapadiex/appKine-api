package com.akine.platform.infrastructure.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.akine.platform.infrastructure.security.ProblemAccessDeniedHandler;
import com.akine.platform.infrastructure.security.ProblemAuthenticationEntryPoint;
import com.akine.platform.spi.config.PerfilesDeEjecucion;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Quien puede leer {@code /actuator/prometheus} (G-4).
 *
 * <h2>La politica</h2>
 * <table>
 *   <caption>Acceso al scrape</caption>
 *   <tr><th>{@code AKINE_METRICS_SCRAPE_TOKEN}</th><th>Perfil</th><th>Resultado</th></tr>
 *   <tr><td>definido</td><td>cualquiera</td>
 *       <td>solo con {@code Authorization: Bearer <token>}; sin el, 401</td></tr>
 *   <tr><td>vacio</td><td>de desarrollo ({@code local}, {@code test}...)</td>
 *       <td>abierto, para mirarlo con el navegador o con {@code curl}</td></tr>
 *   <tr><td>vacio</td><td>cualquier otro, o ninguno</td>
 *       <td>cerrado para todos: 401</td></tr>
 * </table>
 *
 * <h2>Por que una cadena propia y un token estatico, y no el JWT de la aplicacion</h2>
 * Prometheus no inicia sesion: manda una credencial fija en cada scrape. Un access token de
 * AKINE vence a los diez minutos y esta atado a una cuenta humana; ademas, en la cadena
 * principal el {@code JwtAuthenticationFilter} rechaza con 401 cualquier bearer que no sea un
 * JWT valido, asi que el token del scraper nunca llegaria a evaluarse ahi. Por eso esta ruta
 * tiene su propia {@link SecurityFilterChain}, que se evalua antes que la principal y no
 * contiene el filtro de JWT, ni el de tenant, ni el de rate limit.
 *
 * <h2>Por que cerrado por default</h2>
 * Las metricas no llevan datos de pacientes —los tags son de baja cardinalidad, sin ids—, pero
 * dicen cuantos requests recibe cada endpoint, cuanto tardan, cuantos fallan, el tamano del pool
 * de conexiones y la memoria de la JVM. Es informacion operativa que sirve para atacar el
 * sistema (cuando esta saturado, que endpoints son lentos). Es la misma regla que la
 * documentacion del contrato en {@code SecurityConfig}: fuera de desarrollo, lo que no se
 * configura a proposito queda cerrado.
 *
 * <p>La alternativa de servir el actuator en otro puerto ({@code MANAGEMENT_SERVER_PORT}) que el
 * balanceador no publique es compatible con esto y se suma, no lo reemplaza: ver
 * {@code docs/observabilidad.md}.
 */
@Configuration
public class MetricsScrapeSecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(MetricsScrapeSecurityConfig.class);

	/** Ruta del scrape con el base path por default del actuator. */
	static final String RUTA = "/actuator/prometheus";

	/** Largo minimo del token: el mismo piso que el secreto de firma del JWT (ADR-0017). */
	static final int LARGO_MINIMO = 32;

	private static final String PREFIJO_BEARER = "Bearer ";

	private final byte[] huellaDelToken;
	private final boolean abiertoSinToken;

	public MetricsScrapeSecurityConfig(
			@Value("${akine.observability.metrics.scrape-token:}") String token,
			Environment environment) {
		String limpio = token == null ? "" : token.trim();
		if (!limpio.isEmpty() && limpio.length() < LARGO_MINIMO) {
			// Fallar al arrancar y no al primer scrape: un token corto es un token adivinable,
			// y un despliegue que lo tiene asi tiene que enterarse antes de exponerlo.
			throw new IllegalStateException("akine.observability.metrics.scrape-token "
					+ "(AKINE_METRICS_SCRAPE_TOKEN) debe tener al menos " + LARGO_MINIMO
					+ " caracteres.");
		}
		this.huellaDelToken = limpio.isEmpty() ? null : huella(limpio);
		this.abiertoSinToken = PerfilesDeEjecucion.esDesarrollo(environment);
		if (huellaDelToken == null && !abiertoSinToken) {
			log.info("{} queda cerrado: no hay AKINE_METRICS_SCRAPE_TOKEN y ningun perfil de "
					+ "desarrollo esta activo.", RUTA);
		}
	}

	@Bean
	@Order(1)
	public SecurityFilterChain metricsScrapeSecurityFilterChain(HttpSecurity http) throws Exception {
		http
				.securityMatcher(RUTA)
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.requestCache(cache -> cache.disable())
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(new ProblemAuthenticationEntryPoint())
						.accessDeniedHandler(new ProblemAccessDeniedHandler()))
				.authorizeHttpRequests(auth -> auth.anyRequest().access((authentication, contexto) ->
						new AuthorizationDecision(autorizado(contexto.getRequest()))));
		return http.build();
	}

	/** Visible para el test de la politica. */
	boolean autorizado(HttpServletRequest request) {
		if (huellaDelToken == null) {
			return abiertoSinToken;
		}
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null
				|| !header.regionMatches(true, 0, PREFIJO_BEARER, 0, PREFIJO_BEARER.length())) {
			return false;
		}
		String presentado = header.substring(PREFIJO_BEARER.length()).trim();
		// Comparacion en tiempo constante sobre las huellas: ni el contenido ni el largo del
		// token se filtran por el tiempo de respuesta.
		return MessageDigest.isEqual(huellaDelToken, huella(presentado));
	}

	private static byte[] huella(String valor) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException imposible) {
			throw new IllegalStateException("SHA-256 no disponible en la JVM", imposible);
		}
	}
}
