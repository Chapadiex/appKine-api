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
	FERIADO,

	/**
	 * El VINCULO del profesional con la sede no cubria ese dia: no estaba vinculado todavia, o ya
	 * se habia desvinculado (ruling R13 de AKINE-02.04).
	 *
	 * <p>Es el unico valor que el calculador NUNCA produce, y no es una inconsistencia: la
	 * vigencia de la membership no esta entre sus entradas y no puede estarlo sin cambiarle la
	 * firma. Lo pone {@code DisponibilidadEfectivaService} despues de calcular. Vive igual en este
	 * enum —y no como un caso especial pegado al DTO— porque "el vinculo no cubria ese dia" es una
	 * razon de dia vacio como cualquier otra, y la pantalla ya sabe leer este vocabulario.
	 *
	 * <p>Sin el, un dia posterior a la desvinculacion sale como dia sin reglas —vacio y sin
	 * explicacion— y queda indistinguible de un dia en el que simplemente nadie cargo horario.
	 */
	VINCULO
}
