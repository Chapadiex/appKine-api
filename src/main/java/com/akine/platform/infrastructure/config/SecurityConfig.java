package com.akine.platform.infrastructure.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private final List<String> allowedOrigins;

	public SecurityConfig(@Value("${akine.security.cors.allowed-origins}") List<String> allowedOrigins) {
		this.allowedOrigins = allowedOrigins;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
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
				// TODO(F1/M02): reemplazar por autenticacion JWT + autorizacion por contexto.
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
				.build();
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
