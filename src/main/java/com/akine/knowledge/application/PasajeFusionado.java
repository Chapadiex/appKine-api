package com.akine.knowledge.application;

import com.akine.knowledge.spi.PasajeRecuperado;

/**
 * Un pasaje de la busqueda hibrida.
 *
 * @param rrf suma de {@code 1/(k+posicion)} en todas las listas, mas el bonus del libro
 * @param superaCorte similitud de vectores {@code >= corte-vector} en alguna consulta
 * @param coincidePalabra esta en el top de texto completo y su texto contiene una palabra del tema
 */
public record PasajeFusionado(PasajeRecuperado pasaje, double rrf, boolean superaCorte, boolean coincidePalabra) {
}
