package com.akine.knowledge.domain;

/** Fragmento de texto con el rango de paginas que cubre. */
public record Pedazo(int orden, int paginaDesde, int paginaHasta, String texto) {
}
