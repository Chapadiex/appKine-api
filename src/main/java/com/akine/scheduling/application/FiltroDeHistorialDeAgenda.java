package com.akine.scheduling.application;

import java.time.Instant;
import java.util.Set;

/**
 * Que se pide del historial de agenda de un profesional.
 *
 * @param profesionalId membership del profesional en cualquier sede; se resuelve a la persona y se leen
 *                      todas sus memberships de la organizacion
 * @param consultorioId acota a una sede, o {@code null} para todas las que el lector alcanza
 * @param tipos         vacio = todos
 * @param desde         inclusivo, o {@code null}
 * @param hasta         exclusivo, o {@code null}
 * @param texto         "contiene" en el motivo y en el tipo de evento, o {@code null}
 */
public record FiltroDeHistorialDeAgenda(
		long profesionalId,
		Long consultorioId,
		Set<TipoDeCambioDeAgenda> tipos,
		Instant desde,
		Instant hasta,
		String texto) {

	public FiltroDeHistorialDeAgenda {
		tipos = tipos == null ? Set.of() : Set.copyOf(tipos);
		if (desde != null && hasta != null && !hasta.isAfter(desde)) {
			throw new IllegalArgumentException("hasta tiene que ser posterior a desde");
		}
	}
}
