package com.akine.knowledge.domain.port;

import java.math.BigDecimal;

/**
 * Parametros de muestreo de una llamada al modelo de lenguaje. Con {@code json} el modelo se
 * restringe a devolver un objeto JSON (la interpretacion de la consulta).
 */
public record OpcionesGeneracion(BigDecimal temperatura, int maxTokens, boolean json) {

	public OpcionesGeneracion(BigDecimal temperatura, int maxTokens) {
		this(temperatura, maxTokens, false);
	}
}
