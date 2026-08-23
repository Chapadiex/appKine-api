package com.akine.organization.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cadena de seguridad propia de los slices de {@code organization.api}.
 *
 * <p><b>Por que no se importa {@code SecurityConfig}.</b> Estos slices prueban el
 * comportamiento HTTP de los controllers, no la cadena de la aplicacion. Heredar la
 * configuracion global los ataria a lo que esa clase permita en cada momento: el dia que
 * 01.02 cierre la cadena con autenticacion JWT, todos los tests de esta capa empezarian a
 * responder 401 y estarian probando el filtro de autenticacion en vez del controller.
 *
 * <p>Lo que se monta aca es deliberadamente lo minimo que el controller necesita para
 * ejercitarse: {@code permitAll} en la cadena, para que la decision de "quien puede hacer
 * que" quede donde el modulo la puso —{@code ProvisionalAuthorizationGuard} y
 * {@code ApiActor}— y sea eso lo que se verifica.
 *
 * <p>El principal se inyecta por request con el post-processor {@code authentication(..)} de
 * spring-security-test; un request sin el llega con el token anonimo, que es exactamente el
 * caso "no hay sesion autenticada" que debe responder 403 y jamas 401.
 */
@TestConfiguration
@EnableWebSecurity
public class ApiSliceSecurityConfig {

	@Bean
	SecurityFilterChain sliceSecurityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}
}
