package com.akine.scheduling.spi;

import java.time.Instant;

/**
 * Un turno de un Caso Clinico, con lo que hace falta para medir adherencia (M31).
 *
 * <p>Es un hecho <b>administrativo</b>: que un turno este {@code AUSENTE} o {@code CANCELADO} no
 * prueba nada clinico (DP-05). Quien lo consume lo cuenta aparte de las sesiones.
 *
 * @param estado            {@code RESERVADO}, {@code CONFIRMADO}, {@code CANCELADO} o {@code AUSENTE}
 * @param origenCancelacion {@code PACIENTE}, {@code PROFESIONAL} o {@code INSTITUCION}; {@code null}
 *                          si el turno no se cancelo
 */
public record TurnoDelCasoSnapshot(
		long turnoId,
		Instant inicio,
		String estado,
		String origenCancelacion) {
}
