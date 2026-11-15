package com.akine.encounter.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code akine.evolucion.sugerencias.*}: las sugerencias de evolucion de M31 fase 2. Apagadas por
 * defecto; funcionan solo si tambien estan encendidas {@code akine.evolucion.enabled} y
 * {@code akine.biblioteca.enabled}.
 */
@ConfigurationProperties(prefix = "akine.evolucion.sugerencias")
public record SugerenciasProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue Modelo modelo) {

	/**
	 * {@code tipo}: {@code ollama} o {@code prueba}. {@code plazoSegundos}: 50, debajo de los 60 s con
	 * que el nginx del frontend corta {@code /api/} (diseno §5.4). {@code concurrencia}: generaciones
	 * que esperan al modelo a la vez en esta instancia; la siguiente responde 503 {@code modelo-ocupado}.
	 * {@code keepAlive}: el mismo valor que el asistente, para que Ollama no descargue el modelo que
	 * comparten.
	 */
	public record Modelo(
			@DefaultValue("prueba") String tipo,
			@DefaultValue("http://localhost:11434") String url,
			@DefaultValue("qwen3:4b-instruct") String nombre,
			@DefaultValue("50") int plazoSegundos,
			@DefaultValue("1") int concurrencia,
			@DefaultValue("2h") String keepAlive) {
	}
}
