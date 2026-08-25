package com.akine.resource.domain;

/**
 * Que conceptos devuelve una consulta segun su duenio.
 *
 * <p>Es un filtro de lectura y no un alcance de escritura: por eso tiene un valor que
 * {@link CatalogoAlcance} no puede tener, {@link #TODOS}, que es el <b>default</b> y el unico
 * util para un selector — un formulario clinico ofrece las practicas de plataforma y las
 * propias del centro en la misma lista, y no le importa de donde salio cada una.
 *
 * <p>Los otros dos existen para la pantalla de administracion, que si necesita separarlas: lo
 * global no lo puede editar el tenant y mostrarlo mezclado con lo propio invitaria a
 * intentarlo.
 */
public enum CatalogoAlcanceFiltro {

	/** Solo los del catalogo de plataforma. */
	GLOBAL,

	/** Solo los del tenant del contexto. */
	ORGANIZACION,

	/** Los dos, que es lo que un selector necesita. Valor por defecto. */
	TODOS
}
