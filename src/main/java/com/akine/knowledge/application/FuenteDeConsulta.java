package com.akine.knowledge.application;

/** Un pasaje que respalda la respuesta; {@code extracto} son los primeros 300 caracteres. */
public record FuenteDeConsulta(long libroId, String titulo, int paginaDesde, int paginaHasta, String extracto) {
}
