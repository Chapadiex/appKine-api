package com.akine.encounter.domain.evolucion.sugerencias;

import java.time.Duration;

/**
 * La configuracion de las sugerencias de evolucion, ya resuelta.
 *
 * @param habilitadas {@code akine.evolucion.sugerencias.enabled}, {@code akine.evolucion.enabled} y
 *                    {@code akine.biblioteca.enabled} encendidos a la vez
 * @param modelo      nombre del modelo, tal como se guarda en cada sugerencia
 * @param plazo       cuanto se espera al modelo
 */
public record AjustesDeSugerencias(boolean habilitadas, String modelo, Duration plazo) {
}
