package com.akine.activity.application;

/**
 * La inscripcion, si esta llamada fue la que la creo, y como quedaron los cupos.
 *
 * <p>El booleano decide el codigo HTTP: <b>201 si se creo, 200 si se devolvio una existente</b> por
 * idempotencia.
 *
 * <p>Los cupos viajan en la misma respuesta para que la pantalla no tenga que pedirlos aparte
 * justo despues de moverlos — que es ademas el momento en que mas rapido quedan viejos.
 */
public record ResultadoDeInscripcion(InscripcionView inscripcion, CuposView cupos, boolean creada) {
}
