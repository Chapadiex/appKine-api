package com.akine.resource.domain;

/**
 * Que regla produjo o recorto una {@link FranjaEfectiva}. Es la mitad "explicable" del criterio
 * de aceptacion de la etapa: la disponibilidad efectiva tiene que decir por que un martes quedo
 * vacio, y no solo que quedo vacio.
 */
public enum OrigenFranja {

	/** La franja viene de un {@link BloqueDisponibilidad} recurrente. */
	BLOQUE,

	/** Una excepcion {@link TipoExcepcion#APERTURA} la agrego o la amplio. */
	APERTURA,

	/** Una excepcion {@link TipoExcepcion#CIERRE} la recorto o la elimino. */
	CIERRE,

	/** Un {@link Feriado} la cerro porque la sede tiene {@code cierraPorFeriado} activo. */
	FERIADO
}
