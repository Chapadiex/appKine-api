package com.akine.person.application;

/**
 * Filtro por CICLO DE VIDA de una orden o autorizacion.
 *
 * <p>No filtra por vigencia y eso es deliberado: una orden ACTIVA vencida es el caso normal de un
 * paciente que trajo el papel el mes pasado, y "un documento vencido no desaparece" es requisito
 * de la etapa. La vigencia viaja como campo calculado en la vista, no como filtro por defecto.
 */
public enum DocumentoEstadoFiltro {

	/** Solo las vigentes en el ciclo de vida (no dadas de baja). */
	ACTIVA,

	/** Solo las dadas de baja. */
	INACTIVA,

	/** Todas. Es el default: el historial completo es lo que permite explicar el pasado. */
	TODAS
}
