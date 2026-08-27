package com.akine.offering.application;

/**
 * Filtro de estado del listado del catalogo global de Servicios.
 *
 * <p>El default es {@link #ACTIVO} y eso es lo que cumple el criterio de la etapa: <b>las
 * selecciones nuevas excluyen los inactivos</b>. Que haya que pedir explicitamente los dados de
 * baja es la unica forma de que uno no se cuele por descuido en el selector con el que un centro
 * arma una Oferta — y crear una Oferta sobre un servicio inactivo es justamente lo que
 * RF-M27-002 prohibe.
 *
 * <p>{@link #INACTIVO} y {@link #TODOS} existen porque un servicio dado de baja sigue siendo
 * consultable con su nombre y su codigo (RN-M03-006, no afectar historicos), y la pantalla de
 * administracion de plataforma necesita mostrarlos para que se entienda por que un codigo "esta
 * libre" o "esta tomado".
 */
public enum ServicioEstadoFiltro {

	/** Solo servicios vigentes. Lo que ve el selector con el que se crea una Oferta. */
	ACTIVO,

	/** Solo servicios dados de baja. Vista de historico. */
	INACTIVO,

	/** Todos, para la pantalla de administracion de plataforma. */
	TODOS
}
