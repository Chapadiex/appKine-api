package com.akine.knowledge.domain;

import java.math.BigDecimal;

/**
 * Configuracion de la busqueda que necesita la capa de aplicacion
 * ({@code akine.biblioteca.busqueda.*}). Los puntajes van en {@link BigDecimal}: el dominio no
 * guarda punto flotante como campo.
 *
 * @param corteVector puntaje minimo por similitud de vectores ({@code min-score}, 0.818)
 * @param cortePalabras puntaje minimo por palabras ({@code min-score-palabras}, 0.5)
 * @param minPedazos pasajes sobre el corte para que la busqueda "alcance" ({@code min-pedazos}, 2)
 */
public record BusquedaAjustes(BigDecimal corteVector, BigDecimal cortePalabras, int minPedazos) {
}
