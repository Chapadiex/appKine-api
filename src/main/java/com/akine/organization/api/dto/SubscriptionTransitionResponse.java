package com.akine.organization.api.dto;

import com.akine.organization.application.SubscriptionTransitionView;
import com.akine.organization.domain.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un hecho del historico de la suscripcion.
 *
 * <p>El historico es append-only: cada fila registra un cambio que ocurrio, y no se edita ni se
 * borra. La primera fila de toda suscripcion tiene {@code fromStatus} nulo, que significa "alta
 * de la suscripcion": sin ella no habria forma de saber con que plan nacio el tenant.
 *
 * <p>Un cambio de plan aparece con {@code fromStatus == toStatus} y los codigos de plan
 * distintos; una transicion de estado, al reves.
 */
@Schema(description = "Hecho registrado en el historico de la suscripcion")
public record SubscriptionTransitionResponse(

		@Schema(description = "Identificador del hecho", example = "1")
		long id,

		@Schema(
				description = "Estado previo. Ausente en la fila de alta de la suscripcion",
				example = "ACTIVA")
		SubscriptionStatus fromStatus,

		@Schema(description = "Estado resultante", example = "SUSPENDIDA")
		SubscriptionStatus toStatus,

		@Schema(description = "Codigo del plan previo. Ausente en la fila de alta", example = "BASICO")
		String fromPlanCode,

		@Schema(description = "Codigo del plan resultante", example = "PROFESIONAL")
		String toPlanCode,

		@Schema(
				description = "Motivo declarado, cuando la transicion lo exige",
				example = "Falta de pago de tres periodos consecutivos")
		String reason,

		@Schema(
				description = "Cuenta que ejecuto el cambio. Ausente cuando lo origino un proceso "
						+ "automatico",
				example = "42")
		Long actorAccountId,

		@Schema(description = "Instante en que ocurrio el hecho", example = "2026-03-01T10:00:00Z")
		Instant occurredAt) {

	public static SubscriptionTransitionResponse from(SubscriptionTransitionView view) {
		return new SubscriptionTransitionResponse(
				view.id(),
				view.fromStatus(),
				view.toStatus(),
				view.fromPlanCode(),
				view.toPlanCode(),
				view.reason(),
				view.actorAccountId(),
				view.occurredAt());
	}
}
