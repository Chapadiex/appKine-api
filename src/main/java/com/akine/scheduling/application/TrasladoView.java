package com.akine.scheduling.application;

/**
 * El resultado de un cambio de sede: el original, ya cancelado, y el nuevo, que apunta a el con
 * {@code turnoOrigenId}.
 */
public record TrasladoView(TurnoView original, TurnoView nuevo) {
}
