package com.akine.assistant.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracion del asistente de datos ({@code akine.asistente.*}). Apagado por defecto: apagado no
 * crea el modelo y toda consulta responde 409 {@code asistente-deshabilitado}.
 *
 * <p>No depende de {@code akine.biblioteca.*}: los dos pueden apuntar al mismo Ollama y al mismo
 * modelo, pero se prenden por separado.
 */
@ConfigurationProperties(prefix = "akine.asistente")
public record AsistenteProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue Modelo modelo) {

	/**
	 * {@code tipo}: {@code ollama} o {@code prueba}. {@code timeoutSegundos}: el interpretador mide
	 * ~1 s con GPU y 7–9 s solo con CPU (medicion del 15/11), pero la primera consulta con el modelo
	 * descargado suma ~15 s de carga en frio; el default de 60 s cubre esa carga y sigue cortando un
	 * Ollama colgado. {@code concurrencia}: cuantas consultas
	 * esperan al modelo a la vez en esta instancia (ver {@code ModeloConCupo}).
	 */
	public record Modelo(
			@DefaultValue("ollama") String tipo,
			@DefaultValue("http://localhost:11434") String url,
			@DefaultValue("qwen3:4b-instruct") String nombre,
			@DefaultValue("60") int timeoutSegundos,
			@DefaultValue("2h") String keepAlive,
			@DefaultValue("2") int concurrencia) {
	}
}
