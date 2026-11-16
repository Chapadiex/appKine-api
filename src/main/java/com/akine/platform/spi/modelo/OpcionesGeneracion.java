package com.akine.platform.spi.modelo;

import java.math.BigDecimal;

/**
 * Parametros de muestreo de una llamada al modelo de lenguaje. Con {@code json} el modelo se
 * restringe a devolver un objeto JSON (la interpretacion de la consulta).
 *
 * <p>{@code numCtx} es la ventana de contexto que se pide a Ollama ({@code num_ctx}). Hoy solo la usa
 * {@link ModeloDeLenguaje#completarJson(java.util.List, String, OpcionesGeneracion, java.time.Duration)}; las
 * demas operaciones siguen en {@link #NUM_CTX_POR_DEFECTO}. Un valor distinto del que tiene cargado Ollama
 * lo hace recargar el modelo.
 */
public record OpcionesGeneracion(BigDecimal temperatura, int maxTokens, boolean json, int numCtx) {

	public static final int NUM_CTX_POR_DEFECTO = 8192;

	public OpcionesGeneracion {
		if (numCtx <= 0) {
			throw new IllegalArgumentException("num_ctx tiene que ser positivo");
		}
	}

	public OpcionesGeneracion(BigDecimal temperatura, int maxTokens, boolean json) {
		this(temperatura, maxTokens, json, NUM_CTX_POR_DEFECTO);
	}

	public OpcionesGeneracion(BigDecimal temperatura, int maxTokens) {
		this(temperatura, maxTokens, false);
	}
}
