package com.akine.knowledge.domain.port;

import java.math.BigDecimal;

/** Parametros de muestreo de una llamada al modelo de lenguaje. */
public record OpcionesGeneracion(BigDecimal temperatura, int maxTokens) {
}
