package com.akine.knowledge.spi;

import java.util.List;

/**
 * Resultado de una busqueda en la biblioteca.
 *
 * @param corte puntaje minimo del camino usado
 * @param alcanza hay al menos el minimo de pasajes (2) con puntaje sobre el corte: si es
 *     {@code false}, quien llama no deberia apoyarse en la biblioteca para responder
 * @param pasajes por puntaje descendente
 */
public record ResultadoBusqueda(
		CaminoBusqueda camino, double corte, boolean alcanza, List<PasajeRecuperado> pasajes) {

	public ResultadoBusqueda {
		pasajes = List.copyOf(pasajes);
	}
}
