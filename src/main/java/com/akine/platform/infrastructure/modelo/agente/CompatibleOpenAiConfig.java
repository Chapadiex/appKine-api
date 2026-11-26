package com.akine.platform.infrastructure.modelo.agente;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Registra el proveedor compatible con OpenAI cuando el asistente esta prendido y hay una URL en
 * {@code akine.asistente.agente.compatible.url}. El resto de la configuracion sale de {@link AgenteProperties}.
 * El plazo de lectura lo pone cada pedido ({@code PedidoAlAgente.plazo()}): es lo que le queda al bucle.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${akine.asistente.enabled:false} and '${akine.asistente.agente.compatible.url:}' != ''")
class CompatibleOpenAiConfig {

	@Bean
	CompatibleOpenAiProveedor proveedorCompatible(AgenteProperties properties) {
		AgenteProperties.Compatible compatible = properties.compatible();
		HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		return new CompatibleOpenAiProveedor(cliente, compatible.url(), compatible.modelo(), compatible.apiKey(),
				Duration.ofSeconds(properties.plazoTotalSegundos()));
	}
}
