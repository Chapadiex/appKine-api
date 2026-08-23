package com.akine.organization.application;

import java.util.List;

/**
 * Resultado de un cambio de plan.
 *
 * <p>{@code warnings} lista los limites que el uso actual YA excede con el plan nuevo. Son
 * avisos, no rechazos: RN-M01-004 exige que un downgrade no toque datos existentes, asi que el
 * cambio se aplica igual y lo que ya existe sigue operativo. El efecto es prospectivo —la
 * proxima alta que exceda el limite nuevo se rechaza— y el cliente necesita saberlo para
 * poder actuar antes de chocarse.
 *
 * <p>{@code applied} es {@code false} cuando la suscripcion ya estaba en el plan pedido: el
 * replay de un cambio ya aplicado no genera una segunda fila de historico ni un segundo evento
 * de auditoria.
 */
public record PlanChangeOutcome(
		SubscriptionView subscription,
		List<LimitUsageView> warnings,
		boolean applied) {

	public PlanChangeOutcome {
		warnings = List.copyOf(warnings);
	}
}
