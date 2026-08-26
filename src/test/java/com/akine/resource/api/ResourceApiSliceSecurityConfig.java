package com.akine.resource.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cadena de seguridad propia de los slices de {@code resource.api}.
 *
 * <p><b>Por que no se importa {@code SecurityConfig}.</b> Estos slices prueban el comportamiento
 * HTTP de los controllers, no la cadena de la aplicacion. Heredar la configuracion global los
 * ataria a lo que esa clase permita en cada momento y terminarian probando el filtro de
 * autenticacion en vez del controller.
 *
 * <p>Lo que se monta aca es deliberadamente lo minimo: {@code permitAll}, para que la decision de
 * "quien puede hacer que" quede donde el modulo la puso —{@code ApiActor} y el
 * {@code PermissionGuard} del servicio— y sea eso lo que se verifica. Un request sin
 * post-processor llega con el token anonimo, que es exactamente el caso "no hay sesion
 * autenticada" que debe responder 403 y jamas 401.
 */
@TestConfiguration
@EnableWebSecurity
public class ResourceApiSliceSecurityConfig {

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
