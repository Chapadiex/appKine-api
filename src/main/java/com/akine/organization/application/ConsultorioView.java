package com.akine.organization.application;

/**
 * Proyeccion de lectura de un consultorio.
 *
 * <p>Minima a proposito: 01.01 solo necesita identificar la sede para armar el contexto de
 * trabajo. La configuracion completa —horarios, espacios, datos fiscales— la EXPANDE la etapa
 * 02.01 sobre la misma tabla, y este record crece con ella sin renombrar nada.
 */
public record ConsultorioView(long id, long organizationId, String name, boolean active) {
}
