package com.akine.encounter.domain;

/** Si el paciente vino. Una sesion se puede cerrar con AUSENTE: la ausencia tambien es un hecho clinico y economico, y no registrarla haria que el turno quede abierto para siempre. */
public enum Asistencia {
	PRESENTE,
	AUSENTE
}
