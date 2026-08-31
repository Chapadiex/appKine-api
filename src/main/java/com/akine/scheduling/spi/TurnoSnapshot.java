package com.akine.scheduling.spi;

import java.time.Instant;

/**
 * Lo que otro modulo necesita saber de un Turno sin depender de su entidad.
 *
 * <p>No lleva la clave de idempotencia ni el hash del pedido: son protocolo entre el cliente y la
 * API de reservas, y ningun otro modulo tiene por que verlos.
 *
 * @param estado {@code RESERVADO} o {@code CONFIRMADO}. Viaja como texto para que el consumidor no
 *               dependa del enum de {@code scheduling}, que puede crecer en 05.03 sin avisarle
 * @param vivo   {@code false} si el turno esta dado de baja. Un turno cancelado no habilita nada
 */
public record TurnoSnapshot(
		long id,
		long organizationId,
		long consultorioId,
		long ofertaId,
		long personaId,
		Long profesionalMembershipId,
		Long espacioId,
		Instant inicio,
		Instant fin,
		String estado,
		boolean vivo) {
}
