package com.akine.scheduling.application;

import java.time.Instant;

/**
 * Lo que hay que decir para pasar un turno a otra sede de la misma organizacion.
 *
 * <p>Lleva la oferta de destino porque las ofertas son de cada sede: la misma prestacion en otra sede
 * es otra fila de {@code oferta_servicio_consultorio}, con su duracion, su cupo y sus profesionales.
 *
 * @param consultorioDestinoId sede a la que se pasa el turno. Tiene que ser otra
 * @param ofertaDestinoId      oferta de la sede de destino
 * @param inicio               instante de inicio en la sede de destino
 * @param profesionalId        membership del profesional en la sede de destino. {@code null} solo si la
 *                             oferta no lo requiere
 * @param espacioId            box elegido a mano en la sede de destino, o {@code null}
 * @param motivo               por que se traslada. <b>Obligatorio</b> (DP-04): cancela el original
 * @param expectedVersion      version del turno original que leyo el cliente
 */
public record TrasladoCommand(
		long consultorioDestinoId,
		long ofertaDestinoId,
		Instant inicio,
		Long profesionalId,
		Long espacioId,
		String motivo,
		long expectedVersion) {

	public TrasladoCommand {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("El motivo del cambio de sede es obligatorio (DP-04)");
		}
		motivo = motivo.strip();
	}
}
