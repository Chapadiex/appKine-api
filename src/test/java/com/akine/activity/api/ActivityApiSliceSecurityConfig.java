package com.akine.activity.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cadena de seguridad minima para los slices de {@code activity.api}.
 *
 * <p>Deja pasar todo a proposito: el slice verifica el <b>contrato HTTP</b> de los controllers
 * —codigos, Location, forma del cuerpo, validacion—, no la autorizacion, que se decide en la capa
 * de aplicacion sobre el {@code OperatingActor} y la cubren los tests de servicio.
 */
@TestConfiguration
@EnableWebSecurity
public class ActivityApiSliceSecurityConfig {

	@Bean
	SecurityFilterChain activitySliceSecurityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}
}
