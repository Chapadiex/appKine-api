package com.akine.knowledge.domain;

/**
 * Lo que la capa de aplicacion necesita saber de la configuracion de la biblioteca.
 *
 * @param habilitada interruptor {@code akine.biblioteca.enabled}
 */
public record BibliotecaAjustes(boolean habilitada) {
}
