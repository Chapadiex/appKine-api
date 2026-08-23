package com.akine.identity.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cadena de seguridad propia de los slices de {@code identity.api}.
 *
 * <p>Copia el enfoque de {@code organization.api.ApiSliceSecurityConfig} —que no se importa
 * porque pertenece a la capa HTTP de otro modulo— y por el mismo motivo: estos tests prueban el
 * comportamiento del controller, no la cadena de la aplicacion. Heredar {@code SecurityConfig}
 * los ataria a la lista de rutas publicas y convertiria cada test en un test del filtro JWT.
 *
 * <p>{@code permitAll} en la cadena, para que la decision de "quien puede hacer que" quede
 * donde el modulo la puso —{@code IdentityApiActor} y la autorizacion de
 * {@code AccountAdminService}— y sea eso lo que se verifica. El principal se inyecta por
 * request con el post-processor {@code authentication(..)} de spring-security-test; un request
 * sin el llega con el token anonimo, que es el caso "no hay sesion autenticada" y debe dar 403.
 *
 * <p><b>Lo que estos slices NO prueban:</b> la validacion de {@code Origin} del refresh, que
 * vive en la cadena real, y el rate limit. Los dos son filtros de {@code platform} y se
 * verifican en sus propios tests.
 */
@TestConfiguration
@EnableWebSecurity
public class IdentityApiSliceSecurityConfig {

	@Bean
	SecurityFilterChain identitySliceSecurityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}
}
