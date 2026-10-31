package com.akine.scheduling.application;

import java.util.List;

/** Una pagina de los turnos de una persona en una sede. */
public record TurnosDePersonaView(String timezone, long total, List<TurnoDeCalendarioView> turnos) {
}
