package com.akine.platform.infrastructure.modelo.agente;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * El proveedor de prueba del agente, solo con {@code akine.asistente.agente.proveedor=prueba}: sin guiones
 * contesta "Sin guion". Lo usan los tests de integracion, que cargan sus guiones.
 */
@Configuration
@ConditionalOnProperty(name = "akine.asistente.agente.proveedor", havingValue = "prueba")
public class ProveedorDeAgenteDePruebaConfig {

	@Bean
	public ProveedorDeAgenteDePrueba proveedorDeAgenteDePrueba() {
		return new ProveedorDeAgenteDePrueba(Map.of());
	}
}
