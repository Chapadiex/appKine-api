package com.akine.knowledge.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuracion de la biblioteca ({@code akine.biblioteca.*}). Apagada por defecto. */
@ConfigurationProperties(prefix = "akine.biblioteca")
public record BibliotecaProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("104857600") long maxBytes,
		@DefaultValue("2000") int maxPaginas,
		@DefaultValue Troceo troceo,
		@DefaultValue Busqueda busqueda,
		@DefaultValue Modelo modelo,
		@DefaultValue Worker worker,
		@DefaultValue Consulta consulta) {

	public record Troceo(
			@DefaultValue("1000") int tamano,
			@DefaultValue("200") int solape,
			@DefaultValue("100") int minimo) {
	}

	public record Busqueda(
			@DefaultValue("0.818") double minScore,
			@DefaultValue("0.5") double minScorePalabras,
			@DefaultValue("2") int minPedazos,
			@DefaultValue("536870912") long cacheMaxBytes) {
	}

	/**
	 * {@code tipo}: {@code onnx} o {@code prueba}. Las rutas se validan al crear el modelo.
	 * {@code hilos}: tope de hilos de onnxruntime (intra-op) para no ocupar todos los nucleos del
	 * nodo; {@code 0} deja el default de la libreria (un hilo por nucleo).
	 */
	public record Modelo(
			@DefaultValue("onnx") String tipo,
			@DefaultValue("multilingual-e5-base") String id,
			@DefaultValue("") String rutaModelo,
			@DefaultValue("") String rutaTokenizer,
			@DefaultValue("0") int hilos) {
	}

	public record Worker(
			@DefaultValue("true") boolean enabled,
			@DefaultValue("5000") long fixedDelayMs,
			@DefaultValue("30000") long reindexFixedDelayMs,
			@DefaultValue("30000") long initialDelayMs,
			@DefaultValue("32") int reindexLote,
			@DefaultValue("15") int leaseMinutos) {
	}

	/**
	 * Consulta a la biblioteca con un modelo de lenguaje local ({@code akine.biblioteca.consulta.*}).
	 * Apagada por defecto; ademas exige {@code akine.biblioteca.enabled}. {@code tipo}:
	 * {@code ollama} o {@code prueba}.
	 */
	public record Consulta(
			@DefaultValue("false") boolean enabled,
			@DefaultValue("ollama") String tipo,
			@DefaultValue("http://localhost:11434") String url,
			@DefaultValue("qwen2.5:1.5b") String modelo,
			@DefaultValue("90") int timeoutSegundos,
			@DefaultValue("30m") String keepAlive,
			@DefaultValue("3") int maxEnEspera) {
	}
}
