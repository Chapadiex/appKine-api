package com.akine.person.application;

/**
 * Filtro por ciclo de vida de la Persona en la busqueda del padron.
 *
 * <p>Mismo vocabulario que {@code EspacioEstadoFiltro} y {@code ServicioEstadoFiltro}, para que
 * el cliente aprenda un solo filtro de estado en toda la API. El defecto es {@link #ACTIVO}: ver
 * {@code PersonaBusqueda}.
 */
public enum PersonaEstadoFiltro {

	/** Solo las personas vigentes. Es el defecto. */
	ACTIVO,

	/** Solo las dadas de baja. RN-M07-004 exige que sigan siendo consultables. */
	INACTIVO,

	/** Las dos poblaciones. */
	TODOS
}
