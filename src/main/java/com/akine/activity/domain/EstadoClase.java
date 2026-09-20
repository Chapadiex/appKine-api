package com.akine.activity.domain;

/**
 * Estados de una ClaseProgramada en AKINE-08.01.
 *
 * <p><b>Dos y no seis.</b> RN-M28-004 enumera seis estados, pero son los de la <i>inscripcion</i>,
 * no los de la clase: cada participante tiene su propia {@code InscripcionClase} y su propio
 * estado, y eso es 08.02. Mezclar las dos maquinas aqui haria que cancelar una clase y cancelar
 * una inscripcion compartan enum, que es el primer paso para que compartan reglas.
 *
 * <p>Los estados operativos —en curso, realizada— llegan en 08.03, cuando exista asistencia que
 * los justifique. Agregar valores a este enum es aditivo y no rompe ningun cliente.
 */
public enum EstadoClase {

	/** Programada y disponible para inscripciones. Ocupa el recurso. */
	PROGRAMADA,

	/**
	 * Cancelada. <b>Libera el recurso en el acto</b> y conserva la fila.
	 *
	 * <p>Quien libera el horario no es este valor sino la baja logica que lo acompana: las
	 * consultas de solapamiento filtran por {@code deletedAt IS NULL}. El estado es lo que la
	 * pantalla muestra; {@code deleted_at} es lo que la exclusion lee.
	 */
	CANCELADA;

	/** {@code true} si la clase todavia admite que la muevan o la cancelen. */
	public boolean admiteTransicion() {
		return this == PROGRAMADA;
	}
}
