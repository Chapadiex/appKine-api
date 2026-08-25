package com.akine.resource.domain;

/**
 * Que conceptos devuelve una consulta segun su ciclo de vida.
 *
 * <p><b>El default es {@link #ACTIVO}, y ahi esta la mitad del criterio de aceptacion de la
 * etapa:</b> "las nuevas selecciones excluyen inactivos". Un selector que no manda el
 * parametro nunca ofrece un concepto dado de baja, sin que el frontend tenga que acordarse.
 *
 * <p>La otra mitad —"los catalogos historicos siguen resolviendo"— la sostienen
 * {@link #INACTIVO} y {@link #TODOS}, y sobre todo la lectura por id, que devuelve 200 sobre un
 * concepto dado de baja.
 *
 * <p>Es un duplicado deliberado de {@code EspacioEstadoFiltro}: aquel es del catalogo fisico y
 * este del clinico, y unificarlos crearia un enum compartido entre dos modelos que no tienen
 * por que evolucionar juntos.
 */
public enum CatalogoEstadoFiltro {

	/** Solo los vigentes administrativamente. Lo que un selector debe ofrecer. */
	ACTIVO,

	/** Solo los dados de baja. Lo que la pantalla de administracion necesita para explicar. */
	INACTIVO,

	/** Los dos. */
	TODOS
}
