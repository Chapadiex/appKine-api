package com.akine.platform.infrastructure.modelo.agente;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Proveedores del agente del asistente. Existen solo con el asistente prendido; cual se usa lo decide
 * {@code akine.asistente.agente.proveedor}. Sin key de Anthropic el de Claude igual se crea: falla al
 * responder con {@code ModeloNoDisponibleException} y el agente cae al alternativo.
 */
@Configuration
@EnableConfigurationProperties(AgenteProperties.class)
public class ProveedoresDeAgenteConfig {

	@Bean
	@ConditionalOnProperty(name = "akine.asistente.enabled", havingValue = "true")
	public ClaudeProveedor claudeProveedor(AgenteProperties properties) {
		return new ClaudeProveedor(properties);
	}
}
