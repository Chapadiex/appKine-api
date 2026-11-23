package com.akine.assistant.spi;

import java.time.LocalDate;
import java.time.ZoneId;

/** Contexto de la consulta: el dia de hoy en la zona de la sede y la pantalla donde esta la persona. */
public record ContextoDeConsulta(LocalDate hoy, ZoneId zona, String rutaActual) {
}
