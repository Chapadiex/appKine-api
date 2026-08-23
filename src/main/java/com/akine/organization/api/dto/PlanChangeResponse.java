package com.akine.organization.api.dto;

import com.akine.organization.application.PlanChangeOutcome;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Resultado de un cambio de plan.
 *
 * <p><b>{@code warnings} no es una lista de errores.</b> Son los limites que el uso actual ya
 * excede con el plan nuevo. La operacion se aplico igual y nada se borro ni se desactivo: lo
 * que hay sigue funcionando. Lo que cambia es que la proxima alta que supere el limite nuevo se
 * va a rechazar. El cliente los usa para avisar al usuario antes de que se choque, no para
 * mostrar un fallo.
 *
 * <p>{@code applied} distingue "se cambio el plan" de "el plan pedido ya era el vigente". Un
 * reintento del mismo cambio devuelve {@code false} y no genera una segunda fila de historico
 * ni un segundo evento de auditoria: reintentar no puede producir un segundo efecto.
 */
@Schema(description = "Resultado del cambio de plan, con los limites que el uso actual ya excede")
public record PlanChangeResponse(

		@Schema(description = "Suscripcion resultante")
		SubscriptionResponse subscription,

		@Schema(
				description = "Limites que el uso actual YA supera con el plan nuevo. Informativos: "
						+ "nada se borro ni se desactivo, solo se rechazaran las altas futuras que "
						+ "los superen")
		List<LimitUsageResponse> warnings,

		@Schema(
				description = "false cuando el plan pedido ya era el vigente y no hubo cambio",
				example = "true")
		boolean applied) {

	public static PlanChangeResponse from(PlanChangeOutcome outcome) {
		return new PlanChangeResponse(
				SubscriptionResponse.from(outcome.subscription()),
				LimitUsageResponse.fromAll(outcome.warnings()),
				outcome.applied());
	}
}
