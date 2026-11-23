package com.akine.assistant.spi;

import java.util.Set;

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

	/** Como se usa AKINE: lo responde el manual de usuario (knowledge), citandolo textual. */
	COMO_SE_HACE,

	/** Todo lo demas. No tiene herramienta. */
	FUERA_DE_ALCANCE;

	/**
	 * Los reportes de M23 que el asistente sabe pedir. Una sola lista para el interpretador, la
	 * plantilla y la herramienta de {@code reporting}: {@code assistant} no puede importar
	 * {@code reporting.spi.ReporteCode}, porque {@code reporting} ya depende de este {@code spi}.
	 */
	public static final Set<String> REPORTES = Set.of("ECONOMICO", "TURNOS", "CLASES");

	/** Si la intencion habla de una persona del padron. */
	public boolean usaPersona() {
		return this == ASISTENCIA_PERSONA || this == SESION_DE_PERSONA;
	}

	/** Si la intencion habla de una clase grupal. */
	public boolean usaClase() {
		return this == ASISTENTES_CLASE;
	}

	/** Si la intencion pide un reporte. */
	public boolean usaReporte() {
		return this == REPORTE;
	}
}
