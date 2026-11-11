package com.akine.person.application;

/**
 * Filtro por actividades en la busqueda del padron (10/11/2026).
 *
 * <p>Separa a quien hace actividades del centro —clases, pases, abonos— de quien no, igual que
 * {@link PerfilFiltro} separa a los pacientes. Las dos preguntas son independientes: una persona
 * puede ser paciente de kinesiologia y ademas ir a yoga.
 *
 * <p>"Hace actividades" es <b>hoy</b>: tiene una inscripcion reservada o confirmada a una clase
 * que todavia no termino, o un pase o abono activo dentro de su vigencia. Haber ido alguna vez no
 * cuenta: el padron le sirve al mostrador para saber a quien tiene en la sede ahora.
 *
 * <p>El defecto es {@link #TODOS}.
 */
public enum ActividadFiltro {

	/** Solo las que hoy tienen una inscripcion vigente o un pase o abono vigente. */
	CON_ACTIVIDAD,

	/** Solo las que no. */
	SIN_ACTIVIDAD,

	/** Indistinto. Es el defecto. */
	TODOS
}
