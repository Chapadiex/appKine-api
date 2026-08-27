package com.akine.offering.application;

/**
 * Filtro de estado del listado de ofertas de una sede.
 *
 * <p>El default es {@link #ACTIVO}, y eso es lo que cumple el criterio de la etapa: <b>las
 * selecciones nuevas excluyen las ofertas dadas de baja</b>. Que haya que pedirlas explicitamente
 * es la unica forma de que una oferta discontinuada no se cuele por descuido en el selector de un
 * turno cuando llegue la agenda (RN-M27-007).
 *
 * <p>{@link #INACTIVO} y {@link #TODOS} existen porque una oferta dada de baja sigue siendo
 * consultable con su nombre comercial y su estado, y la pantalla de administracion necesita
 * mostrarlas para que el usuario entienda por que un nombre comercial "esta libre" o "esta
 * tomado". Mismo enum, y mismo motivo, que {@code EspacioEstadoFiltro} en 02.02.
 *
 * <p><b>No hay un filtro por vigencia.</b> "Vigente hoy" es una pregunta distinta —depende de
 * {@code vigenciaDesde} / {@code vigenciaHasta} y de la fecha— y la responde
 * {@code OfertaView.vigenteHoy} sobre cada fila, sin una consulta propia. Materializarla como
 * filtro exigiria una query nueva en el puerto, y todavia no hay ningun llamador que la necesite:
 * la agenda llega en F5.
 */
public enum OfertaEstadoFiltro {

	/** Solo ofertas vigentes administrativamente. Lo que veria un selector de reserva. */
	ACTIVO,

	/** Solo las dadas de baja. Vista de historico. */
	INACTIVO,

	/** Todas, para la pantalla de administracion. */
	TODOS
}
