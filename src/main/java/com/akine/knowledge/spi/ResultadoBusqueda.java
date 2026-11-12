package com.akine.knowledge.spi;

import java.util.List;

/**
 * Resultado de una busqueda en la biblioteca.
 *
 * @param camino por similitud ({@code VECTOR}) o por palabras ({@code PALABRAS})
 * @param corte puntaje minimo del camino usado
 * @param alcanza hay al menos {@code minPedazos} pasajes (2 por defecto) con puntaje sobre el
 *     corte. Si es {@code false}, quien llama no deberia apoyarse en la biblioteca para responder.
 *     Con {@code k < minPedazos} es siempre {@code false}, y con la biblioteca apagada tambien
 *     (el resultado viene vacio).
 * @param pasajes por puntaje descendente
 */
public record ResultadoBusqueda(
		CaminoBusqueda camino, double corte, boolean alcanza, List<PasajeRecuperado> pasajes) {

	public ResultadoBusqueda {
		pasajes = List.copyOf(pasajes);
	}
}
