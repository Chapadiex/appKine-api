package com.akine.platform.infrastructure.modelo.agente;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;
import java.time.Duration;

/** Registra el proveedor compatible con OpenAI cuando hay una URL configurada. Provisoria: la unifica AgenteProperties. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${akine.asistente.enabled:false} and '${akine.asistente.agente.compatible.url:}' != ''")
class CompatibleOpenAiConfig {

	@Bean
	CompatibleOpenAiProveedor proveedorCompatible(
			@Value("${akine.asistente.agente.compatible.url}") String url,
			@Value("${akine.asistente.agente.compatible.modelo:}") String modelo,
			@Value("${akine.asistente.agente.compatible.api-key:}") String apiKey) {
		JdkClientHttpRequestFactory fabrica = new JdkClientHttpRequestFactory(
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
		fabrica.setReadTimeout(Duration.ofSeconds(30));
		return new CompatibleOpenAiProveedor(RestClient.builder().requestFactory(fabrica), url, modelo, apiKey);
	}
}
