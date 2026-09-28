package com.akine.activity.domain;

/** Que le paso a una asistencia. Ver {@link AsistenciaEvento}: el historial es append-only. */
public enum TipoEventoAsistencia {

	/** Primera vez que se afirma el hecho. */
	REGISTRO,

	/**
	 * Alguien corrigio un hecho ya afirmado. <b>Exige motivo</b>: corregir cambia lo que el sistema
	 * decia que paso, y el por que es lo unico que hace auditable esa correccion.
	 */
	CORRECCION
}
