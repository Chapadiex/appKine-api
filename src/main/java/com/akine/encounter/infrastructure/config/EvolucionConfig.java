package com.akine.encounter.infrastructure.config;

import com.akine.encounter.domain.evolucion.ConfiguracionDeEvolucion;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registra {@code akine.evolucion.*} y la expone a {@code application} como un record del dominio. */
@Configuration
@EnableConfigurationProperties(EvolucionProperties.class)
public class EvolucionConfig {

	@Bean
	ConfiguracionDeEvolucion configuracionDeEvolucion(EvolucionProperties properties) {
		return properties.aConfiguracion();
	}
}
