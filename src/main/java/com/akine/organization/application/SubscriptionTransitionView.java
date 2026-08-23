package com.akine.organization.application;

import com.akine.organization.domain.SubscriptionStatus;

import java.time.Instant;

/**
 * Una fila del historico de la suscripcion.
 *
 * <p>Registra tanto cambios de estado como cambios de plan: en un cambio de plan los dos
 * estados son ACTIVA y lo que cambia son los planes. Asi el historico responde "que le paso a
 * esta suscripcion" con una sola consulta.
 *
 * <p>{@code actorAccountId} en {@code null} significa el SISTEMA (onboarding automatico, job,
 * migracion). Es informacion, no un dato faltante.
 */
public record SubscriptionTransitionView(
		long id,
		SubscriptionStatus fromStatus,
		SubscriptionStatus toStatus,
		String fromPlanCode,
		String toPlanCode,
		String reason,
		Long actorAccountId,
		Instant occurredAt) {
}
