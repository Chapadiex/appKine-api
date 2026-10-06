package com.akine.offering.spi;

/**
 * Una practica que una oferta declara poder prestar (A-9, DP-11).
 *
 * @param practicaId id de la practica del catalogo M06 ({@code resource.spi.CatalogoDirectory}
 *                   resuelve su codigo y nombre)
 * @param principal  si es la practica por defecto de la oferta: la que se devenga o consume cuando
 *                   la sesion cierra sin tratamientos
 */
public record PracticaDeOferta(long practicaId, boolean principal) {
}
