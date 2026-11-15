package com.akine.assistant.spi;

/**
 * Lo que el asistente sabe responder. El modelo de lenguaje solo elige una de estas: cualquier otra
 * cosa que devuelva se trata como {@link #FUERA_DE_ALCANCE}.
 *
 * <p>Renombrar un valor cambia el prompt del interpretador y el set de evaluacion
 * ({@code docs/asistente/evaluacion/}): se miden juntos.
 */
public enum Intencion {

	/** Si una persona vino, falto o cuantas veces vino a sus turnos. */
	ASISTENCIA_PERSONA,

	/** Si a una persona le cargaron o cerraron la sesion. */
	SESION_DE_PERSONA,

	/** Que turnos hay un dia. */
	AGENDA_DEL_DIA,

	/** Quienes vinieron o faltaron a una clase grupal. */
	ASISTENTES_CLASE,

	/** Numeros del centro en un periodo: un reporte de M23. */
	REPORTE,

	/** Busqueda de personas para resolver un nombre. Interna: el modelo no la elige. */
	BUSCAR_PERSONA,

	/** Todo lo demas. No tiene herramienta. */
	FUERA_DE_ALCANCE
}
