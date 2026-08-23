package com.akine.platform.infrastructure.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.akine.platform.infrastructure.tenant.TenantContextFilter;

/**
 * Base segura del backend para AKINE-00.01.
 *
 * <p><b>Alcance de esta etapa:</b> headers de seguridad, CORS con origenes explicitos y
 * politica stateless. La etapa no implementa identidad, tenant ni permisos: eso corresponde
 * a F1 (M01-M02), segun el plan.
 *
 * <p><b>Por que la cadena permite todo:</b> todavia no existe ningun endpoint de negocio.
 * Los unicos endpoints publicados son el contrato tecnico de version y el health de
 * Actuator, ambos deliberadamente publicos. En F1 esta cadena se reemplaza por autenticacion
 * JWT y autorizacion por contexto (organizacion + consultorio), y el {@code permitAll} pasa
 * a ser la excepcion explicita, no la regla.
 *
 * <p>CSRF queda deshabilitado porque la API es stateless y no usa cookies de sesion. Cuando
 * F1 introduzca el refresh token en cookie httpOnly, CSRF debe reactivarse para el endpoint
 * de refresh.
 *
 * <p><b>Que agrego AKINE-01.01:</b> unicamente la resolucion de contexto multi-tenant
 * ({@link TenantContextFilter}). La autenticacion sigue sin implementarse —es 01.02— y la
 * cadena sigue siendo permisiva a proposito. Las dos cosas son independientes: el filtro de
 * contexto no autentica a nadie, solo revalida contra la base el contexto que el principal ya
 * autenticado dice tener.
 *
 * <p><b>Orden esperado de filtros al terminar F1:</b>
 * <pre>
 *   SecurityContextHolderFilter
 *     -> [01.02] JwtAuthenticationFilter      (pone el AuthenticatedPrincipal en el contexto)
 *     -> AuthorizationFilter                   (aplica authorizeHttpRequests)
 *     -> TenantContextFilter                   (revalida contexto contra la base y lo publica)
 *     -> DispatcherServlet
 * </pre>
 * El contexto se resuelve DESPUES de autenticar —antes no hay principal que revalidar— y
 * despues de autorizar la ruta, para no pegarle a la base por un request que la cadena va a
 * rechazar igual. Cuando 01.02 inserte su filtro de autenticacion, va antes de
 * {@code AuthorizationFilter}; este no se mueve.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

	private final List<String> allowedOrigins;
	private final ObjectProvider<TenantContextFilter> tenantContextFilter;

	/**
	 * @param tenantContextFilter el filtro de contexto, si esta en el contexto de aplicacion.
	 *                            Se recibe como {@link ObjectProvider} y no como dependencia
	 *                            obligatoria porque los slices {@code @WebMvcTest} importan
	 *                            esta configuracion sin el modulo de tenancy ni sus
	 *                            repositorios; exigirlo los volveria imposibles de levantar.
	 *                            En la aplicacion completa siempre esta, y si faltara se avisa
	 *                            con un WARN: una cadena sin resolucion de contexto no puede
	 *                            pasar inadvertida.
	 */
	public SecurityConfig(
			@Value("${akine.security.cors.allowed-origins}") List<String> allowedOrigins,
			ObjectProvider<TenantContextFilter> tenantContextFilter) {
		this.allowedOrigins = allowedOrigins;
		this.tenantContextFilter = tenantContextFilter;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.cors(Customizer.withDefaults())
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.headers(headers -> headers
						.frameOptions(frame -> frame.deny())
						.contentTypeOptions(Customizer.withDefaults())
						.httpStrictTransportSecurity(hsts -> hsts
								.includeSubDomains(true)
								.maxAgeInSeconds(31_536_000))
						.referrerPolicy(referrer -> referrer.policy(
								org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
										.ReferrerPolicy.NO_REFERRER)))
				// TODO(F1/M02): reemplazar por autenticacion JWT. La autorizacion por contexto
				// ya la resuelve TenantContextFilter; lo que falta es el "quien sos".
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

		TenantContextFilter filtroDeContexto = tenantContextFilter.getIfAvailable();
		if (filtroDeContexto == null) {
			log.warn("La cadena de seguridad se arma SIN resolucion de contexto multi-tenant. "
					+ "Esperable solo en un slice de test; en la aplicacion completa es un error "
					+ "de configuracion.");
		} else {
			http.addFilterAfter(filtroDeContexto, AuthorizationFilter.class);
		}

		return http.build();
	}

	/**
	 * Origenes permitidos por configuracion, nunca comodin.
	 *
	 * <p>{@code allowCredentials(true)} es necesario para el refresh token en cookie
	 * httpOnly que llega en F1, y es incompatible con {@code allowedOrigins("*")}: por eso
	 * los origenes se declaran explicitamente por entorno.
	 */
	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins);
		configuration.setAllowedMethods(List.of(
				HttpMethod.GET.name(),
				HttpMethod.POST.name(),
				HttpMethod.PUT.name(),
				HttpMethod.PATCH.name(),
				HttpMethod.DELETE.name(),
				HttpMethod.OPTIONS.name()));
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
		configuration.setAllowCredentials(true);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}
}
