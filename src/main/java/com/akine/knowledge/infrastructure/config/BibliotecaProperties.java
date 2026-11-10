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
		@DefaultValue Worker worker) {

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

	/** {@code tipo}: {@code onnx} o {@code prueba}. Las rutas se validan al crear el modelo. */
	public record Modelo(
			@DefaultValue("onnx") String tipo,
			@DefaultValue("multilingual-e5-base") String id,
			@DefaultValue("") String rutaModelo,
			@DefaultValue("") String rutaTokenizer) {
	}

	public record Worker(
			@DefaultValue("true") boolean enabled,
			@DefaultValue("5000") long fixedDelayMs,
			@DefaultValue("32") int reindexLote,
			@DefaultValue("15") int leaseMinutos) {
	}
}
