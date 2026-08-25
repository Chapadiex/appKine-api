package com.akine.resource.application;

/**
 * Filtro de estado del listado de espacios (RNF-M04-004).
 *
 * <p>El default es {@link #ACTIVO} y eso es lo que cumple el criterio de aceptacion de la
 * etapa: <b>las selecciones nuevas excluyen los inactivos</b>. Que haya que pedir
 * explicitamente los dados de baja es la unica forma de que uno no se cuele por descuido en el
 * selector de un turno.
 *
 * <p>{@link #INACTIVO} y {@link #TODOS} existen porque un espacio dado de baja sigue siendo
 * consultable con su nombre y su estado (RN-M04-003), y la pantalla de administracion necesita
 * mostrarlos para que el usuario entienda por que un nombre "esta libre" o "esta tomado".
 */
public enum EspacioEstadoFiltro {

	/** Solo espacios vigentes. Lo que ve un selector de reserva. */
	ACTIVO,

	/** Solo espacios dados de baja. Vista de historico. */
	INACTIVO,

	/** Todos, para la pantalla de administracion. */
	TODOS
}
