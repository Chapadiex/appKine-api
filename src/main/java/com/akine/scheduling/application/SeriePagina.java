package com.akine.scheduling.application;

import java.util.List;

/**
 * Una pagina de la bandeja de series (AKINE E-8). El recorte ocurre en la base, asi que el total
 * se cuenta aparte con el mismo filtro.
 *
 * @param total cuantas series cumplen el filtro en la sede, no en esta pagina
 */
public record SeriePagina(List<SerieResumenView> contenido, long total) {

	public SeriePagina {
		contenido = List.copyOf(contenido);
	}
}
