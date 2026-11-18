package com.akine.organization.infrastructure;

import com.akine.organization.application.IaDelDespliegue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lee los interruptores de despliegue de cada funcion de IA. Son las mismas propiedades que usan
 * {@code assistant}, {@code encounter} y {@code knowledge}: se leen como texto de configuracion,
 * sin importar esos modulos.
 */
@Configuration
public class IaDelDespliegueConfig {

	@Bean
	IaDelDespliegue iaDelDespliegue(
			@Value("${akine.asistente.enabled:false}") boolean asistente,
			@Value("${akine.evolucion.enabled:false}") boolean evolucion,
			@Value("${akine.evolucion.sugerencias.enabled:false}") boolean sugerencias,
			@Value("${akine.biblioteca.enabled:false}") boolean biblioteca) {
		return new IaDelDespliegue(asistente, evolucion, sugerencias, biblioteca);
	}
}
