package com.akine.encounter.domain;

/**
 * Cuantas sesiones cerradas de un Caso corresponden a una oferta, repartidas por asistencia.
 *
 * <p>Es el resultado de una agregacion, no una fila de ninguna tabla: lo construye la consulta con
 * una expresion de constructor. No hay —y no tiene que haber— una tabla que guarde esto, porque
 * seria una segunda copia de la verdad que se desincroniza en cuanto alguien cierre una sesion por
 * un camino que no le avise al proyector.
 *
 * <p>Lo consume {@code encounter.infrastructure.EncounterRealizadoEnElCasoProbe}, que lo traduce a
 * la forma que declara {@code clinical.spi}. Los tipos son {@code Long} porque {@code SUM} en JPQL
 * devuelve {@code Long} y una expresion de constructor no convierte: declarar {@code int} haria
 * fallar la consulta al arrancar la aplicacion.
 */
public record ConteoDeSesionesPorOferta(Long ofertaId, Long realizadas, Long canceladas) {
}
