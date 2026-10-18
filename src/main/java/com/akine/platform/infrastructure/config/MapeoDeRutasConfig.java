package com.akine.platform.infrastructure.config;

import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Registra {@link MapeoDeRutasConDescarte} como el {@code RequestMappingHandlerMapping} de Spring
 * MVC. Spring Boot lo sigue configurando entero (analizador de rutas, interceptores, CORS,
 * negociacion de contenido): solo cambia la instancia (docs/carga.md §13).
 */
@Configuration(proxyBeanMethods = false)
public class MapeoDeRutasConfig {

	@Bean
	WebMvcRegistrations mapeoDeRutasConDescarte() {
		return new WebMvcRegistrations() {
			@Override
			public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
				return new MapeoDeRutasConDescarte();
			}
		};
	}
}
