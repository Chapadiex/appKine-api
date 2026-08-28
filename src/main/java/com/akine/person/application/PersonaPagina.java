package com.akine.person.application;

import java.util.List;

/**
 * Una pagina del padron: el contenido y el total del filtro.
 *
 * <p>Existe porque el recorte ocurre en la BASE y no en memoria, a diferencia de los listados de
 * espacios y ofertas: el total no se puede deducir del tamanio de la lista devuelta, hay que
 * contarlo aparte. Ver {@code PersonaRepositoryPort.buscar}.
 *
 * @param total cuantas personas cumplen el filtro en toda la organizacion, no en esta pagina
 */
public record PersonaPagina(List<PersonaView> contenido, long total) {

	public PersonaPagina {
		contenido = List.copyOf(contenido);
	}
}
