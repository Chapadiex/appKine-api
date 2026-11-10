package com.akine.knowledge.domain;

/**
 * Lo que la capa de aplicacion necesita saber de la configuracion de la biblioteca.
 *
 * @param habilitada interruptor {@code akine.biblioteca.enabled}
 * @param maxBytes tamano maximo de un PDF
 */
public record BibliotecaAjustes(boolean habilitada, long maxBytes) {
}
