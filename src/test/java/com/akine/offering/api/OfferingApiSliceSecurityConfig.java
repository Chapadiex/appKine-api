package com.akine.offering.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cadena de seguridad minima para los slices de {@code offering.api}.
 *
 * <p>Deja pasar todo a proposito: el slice verifica el <b>contrato HTTP</b> del controller
 * —codigos, forma del cuerpo, negociacion de contenido—, no la autorizacion. Quien autoriza es
 * la capa de aplicacion, y eso lo cubren {@code ServicioServiceTest} y {@code OfertaServiceTest}
 * sobre el {@code OperatingActor}, que es donde la decision realmente se toma.
 */
@TestConfiguration
@EnableWebSecurity
public class OfferingApiSliceSecurityConfig {

	@Bean
	SecurityFilterChain offeringSliceSecurityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session
						.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}
}
