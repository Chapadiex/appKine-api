package com.akine.platform.spi.modelo.agente;

/** Lo que devolvio una herramienta para la llamada {@code llamadaId}. */
public record ResultadoDeLlamada(String llamadaId, String contenidoJson, boolean error) {
}
