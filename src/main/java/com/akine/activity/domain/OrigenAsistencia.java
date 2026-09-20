package com.akine.activity.domain;

/**
 * De donde salio el registro de asistencia (AKINE-08.03).
 *
 * <p><b>No es decorativo.</b> Distinguir "alguien la marco" de "la puso el cierre porque nadie la
 * marco" es lo que hace que una ausencia declarada y un no-show por omision no se confundan — y esa
 * distincion va a importar cuando el no-show cueste plata (08.07). Sin esta columna, las dos filas
 * serian identicas y la politica economica no tendria como separarlas.
 */
public enum OrigenAsistencia {

	/** La marco una persona, de a una, desde la pantalla de la clase. */
	MOSTRADOR,

	/** Vino en un lote de la lista compacta (RF-M13-008). */
	LOTE,

	/**
	 * La puso el cierre operativo de la clase porque nadie la habia marcado.
	 *
	 * <p>Siempre con resultado {@link ResultadoAsistencia#AUSENTE}: el cierre no puede afirmar que
	 * alguien vino, solo que nadie dijo que viniera.
	 */
	CIERRE,

	/**
	 * La persona se presento sin estar inscripta y se la dejo entrar (RF-M13-007).
	 *
	 * <p>Esa operacion <b>creo la inscripcion tomando el lugar de verdad</b>: no hay asistencia sin
	 * inscripcion, porque una asistencia huerfana es una persona adentro de la clase que el
	 * contador de cupo no ve.
	 */
	INGRESO_SIN_INSCRIPCION
}
