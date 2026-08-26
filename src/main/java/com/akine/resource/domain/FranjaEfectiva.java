package com.akine.resource.domain;

/**
 * Una franja de atencion ya resuelta, con la trazabilidad de como quedo asi.
 *
 * <p>{@code origen} y {@code recortadoPor} no son decoracion: el criterio de aceptacion de la
 * etapa pide que la disponibilidad efectiva <b>explique que regla la afecta</b>. Sin estos
 * dos campos el CA no se puede declarar cubierto, y la pantalla no puede decirle al admin por
 * que un martes quedo vacio.
 *
 * @param recortadoPor {@code null} si nada la recorto
 * @param reglaId      id de la fila que la produjo, para que la UI pueda linkearla
 */
public record FranjaEfectiva(
		IntervaloLocal intervalo,
		OrigenFranja origen,
		OrigenFranja recortadoPor,
		Long reglaId) {
}
