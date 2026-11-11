package com.akine.knowledge.domain.port;

/** Parametros de muestreo de una llamada al modelo de lenguaje. */
public record OpcionesGeneracion(double temperatura, int maxTokens) {
}
