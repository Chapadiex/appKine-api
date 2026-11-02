package com.akine.scheduling.application;

/**
 * Un turno del calendario: el mismo turno de la recepcion mas el nombre del profesional.
 *
 * <p>El nombre no esta en {@link TurnoDelDiaView} y no se agrega ahi: la recepcion no lo pidio y
 * cambiarle la forma a la agenda del dia no es de este cambio. Se resuelve aca, en lote.
 *
 * @param profesionalNombre nombre de la cuenta del profesional, o {@code null} si el turno no
 *                          tiene profesional o su cuenta ya no se puede resolver
 */
public record TurnoDeCalendarioView(TurnoDelDiaView turno, String profesionalNombre) {
}
