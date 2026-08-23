package com.akine.organization.api.dto;

import com.akine.organization.application.SubscriptionView;
import com.akine.organization.domain.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Suscripcion del tenant: plan contratado, estado y consumo de limites.
 *
 * <p>{@code allowedTargets} publica las transiciones que la maquina de estados admite AHORA.
 * El frontend no replica la maquina: pinta los botones que el backend dice que existen. Si la
 * duplicara, cualquier cambio de reglas obligaria a desplegar los dos lados en el mismo
 * instante para no ofrecer acciones que fallan.
 *
 * <p>{@code version} es la que hay que enviar en el cambio de plan, por el mismo motivo que la
 * de la organizacion: sin ella, dos cambios concurrentes se pisan.
 */
@Schema(description = "Suscripcion del tenant con su plan, estado y consumo de limites")
public record SubscriptionResponse(

		@Schema(description = "Identificador de la suscripcion", example = "1")
		long id,

		@Schema(description = "Organizacion titular de la suscripcion", example = "1")
		long organizationId,

		@Schema(description = "Codigo del plan contratado", example = "BASICO")
		String planCode,

		@Schema(description = "Nombre comercial del plan contratado", example = "Basico")
		String planName,

		@Schema(
				description = "Estado de la suscripcion. SUSPENDIDA permite lecturas y "
						+ "administracion, y rechaza mutaciones de negocio con 409. CANCELADA es "
						+ "terminal",
				example = "ACTIVA")
		SubscriptionStatus status,

		@Schema(description = "Instante de alta de la suscripcion", example = "2026-01-15T13:45:00Z")
		Instant startedAt,

		@Schema(
				description = "Version para bloqueo optimista. Devolverla tal cual al cambiar de plan",
				example = "0")
		long version,

		@Schema(description = "Limites del plan con el consumo actual de cada uno")
		List<LimitUsageResponse> limits,

		@Schema(
				description = "Estados a los que se puede transicionar desde el actual. Vacio "
						+ "cuando el estado es terminal",
				example = "[\"SUSPENDIDA\", \"CANCELADA\"]")
		Set<SubscriptionStatus> allowedTargets) {

	public static SubscriptionResponse from(SubscriptionView view) {
		return new SubscriptionResponse(
				view.id(),
				view.organizationId(),
				view.planCode(),
				view.planName(),
				view.status(),
				view.startedAt(),
				view.version(),
				LimitUsageResponse.fromAll(view.limits()),
				view.allowedTargets());
	}
}
