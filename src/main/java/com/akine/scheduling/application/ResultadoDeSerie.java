package com.akine.scheduling.application;

/** El alta de una serie: la serie y si se creo ahora o es el reintento de una ya creada. */
public record ResultadoDeSerie(SerieView serie, boolean creada) {
}
