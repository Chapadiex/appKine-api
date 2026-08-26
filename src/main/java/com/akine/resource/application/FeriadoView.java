package com.akine.resource.application;

import com.akine.resource.domain.Feriado;

import java.time.LocalDate;

/**
 * Proyeccion de lectura de un feriado del calendario nacional.
 *
 * <p>No lleva {@code organizationId} y no es un olvido: la tabla {@code feriado} es GLOBAL
 * (ADR-0022, V22). Un feriado nacional no es de nadie; la decision que si es de cada sede —si
 * cierra ese dia— viaja en {@link CalendarioView#cierraPorFeriado()}.
 *
 * @param tipo texto y no enum: la lista valida la fija el CHECK de V22 y esta etapa no ramifica
 *             comportamiento por tipo, solo lo muestra
 */
public record FeriadoView(long id, String pais, LocalDate fecha, String nombre, String tipo) {

	public static FeriadoView de(Feriado feriado) {
		return new FeriadoView(
				feriado.getId(),
				feriado.getPais(),
				feriado.getFecha(),
				feriado.getNombre(),
				feriado.getTipo());
	}
}
