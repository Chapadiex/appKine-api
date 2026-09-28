package com.akine.clinical.spi;

/**
 * De que clase de participacion viene una derivacion.
 *
 * <p>Hoy hay un solo valor y el enum existe igual. El motivo: {@code participacion_id} es un id
 * <b>sin FK</b> —ver la cabecera de {@code V63}, punto 4—, asi que nada en el esquema dice de que
 * tabla es. Sin este discriminador, la primera etapa que agregue otra fuente de participacion
 * tendria que adivinar, o peor, mirar las dos tablas.
 */
public enum OrigenDeParticipacion {

	/** Una asistencia a una clase programada (M28). {@code asistencia_actividad.id}. */
	CLASE_PROGRAMADA
}
