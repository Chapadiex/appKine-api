package com.akine.activity.domain;

/**
 * Estados de una ClaseProgramada en AKINE-08.01.
 *
 * <p><b>Dos y no seis.</b> RN-M28-004 enumera seis estados, pero son los de la <i>inscripcion</i>,
 * no los de la clase: cada participante tiene su propia {@code InscripcionClase} y su propio
 * estado, y eso es 08.02. Mezclar las dos maquinas aqui haria que cancelar una clase y cancelar
 * una inscripcion compartan enum, que es el primer paso para que compartan reglas.
 *
 * <p><b>AKINE-08.03 los agrego.</b> Lo que aquel javadoc anunciaba —en curso, realizada— existe
 * desde que hay asistencia que lo justifique. Agregar valores es aditivo: el tipo no cambia y
 * ningun cliente se rompe; lo unico que se queda corto es un {@code switch} exhaustivo sobre el
 * enum generado, y eso se resuelve al regenerar.
 */
public enum EstadoClase {

	/** Programada y disponible para inscripciones. Ocupa el recurso. */
	PROGRAMADA,

	/**
	 * La clase abrio y se puede tomar lista (RF-M13-007).
	 *
	 * <p><b>Sigue ocupando el recurso</b> y sigue siendo una clase viva: no se puede reprogramar
	 * —eso exige {@code PROGRAMADA}— pero se puede cancelar, y se puede recibir a alguien que llega
	 * sin estar inscripto.
	 */
	EN_CURSO,

	/**
	 * La clase termino y su operacion esta cerrada.
	 *
	 * <p>Cerrar <b>no cobra</b> y no devenga nada: es la misma regla que DP-06 le fijo al cierre de
	 * Sesion, y el motivo concreto esta en la §6 del diseno de 08.03 — la politica de devengo por
	 * clase no existe todavia en ninguna tabla.
	 *
	 * <p>Una clase realizada <b>todavia admite correcciones de asistencia</b>, porque es justo
	 * cuando se descubren. Lo que no admite es reprogramarse ni cancelarse: lo que paso, paso.
	 */
	REALIZADA,

	/**
	 * Cancelada. <b>Libera el recurso en el acto</b> y conserva la fila.
	 *
	 * <p>Quien libera el horario no es este valor sino la baja logica que lo acompana: las
	 * consultas de solapamiento filtran por {@code deletedAt IS NULL}. El estado es lo que la
	 * pantalla muestra; {@code deleted_at} es lo que la exclusion lee.
	 */
	CANCELADA;

	/** {@code true} si la clase todavia admite que la muevan. Reprogramar exige no haber empezado. */
	public boolean admiteTransicion() {
		return this == PROGRAMADA;
	}

	/**
	 * {@code true} si se puede registrar o corregir asistencia.
	 *
	 * <p>Que haya que iniciar la clase antes de tomar lista es lo que hace que {@link #EN_CURSO}
	 * sirva para algo: sin esa condicion seria un valor decorativo. Y {@link #REALIZADA} entra
	 * porque <b>las correcciones llegan despues de que la clase termino</b>, que es cuando se
	 * descubren.
	 */
	public boolean admiteAsistencia() {
		return this == EN_CURSO || this == REALIZADA;
	}

	/** {@code true} si la clase ya cerro su operacion. */
	public boolean estaCerrada() {
		return this == REALIZADA;
	}
}
