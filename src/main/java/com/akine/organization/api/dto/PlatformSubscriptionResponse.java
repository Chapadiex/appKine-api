package com.akine.organization.api.dto;

import com.akine.organization.application.SubscriptionView;
import com.akine.organization.domain.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Set;

/**
 * La suscripcion de un centro vista por la administracion de plataforma: el contrato, no los datos.
 *
 * <p>Es {@link SubscriptionResponse} sin {@code limits}. El consumo de cada limite cuenta sedes y
 * miembros del centro, que son datos del tenant y siguen detras de {@code tenant:read} con acceso
 * de soporte. Lo que queda —plan, estado, version y transiciones posibles— es lo que la plataforma
 * misma escribe al cambiar el plan o el estado, y la {@code version} es justamente lo que el
 * cambio de plan le exige.
 */
@Schema(description = "Suscripcion de un centro para la administracion de plataforma: plan, "
		+ "estado y version, sin el consumo de limites")
public record PlatformSubscriptionResponse(

		@Schema(description = "Identificador de la suscripcion", example = "1")
		long id,

		@Schema(description = "Organizacion titular de la suscripcion", example = "1")
		long organizationId,

		@Schema(description = "Codigo del plan contratado", example = "BASICO")
		String planCode,

		@Schema(description = "Nombre comercial del plan contratado", example = "Basico")
		String planName,

		@Schema(description = "Estado de la suscripcion", example = "ACTIVA")
		SubscriptionStatus status,

		@Schema(description = "Instante de alta de la suscripcion", example = "2026-01-15T13:45:00Z")
		Instant startedAt,

		@Schema(
				description = "Version para bloqueo optimista. Es la expectedVersion del cambio de "
						+ "plan",
				example = "0")
		long version,

		@Schema(
				description = "Estados a los que se puede transicionar desde el actual. Vacio "
						+ "cuando el estado es terminal",
				example = "[\"SUSPENDIDA\", \"CANCELADA\"]")
		Set<SubscriptionStatus> allowedTargets) {

	public static PlatformSubscriptionResponse from(SubscriptionView view) {
		return new PlatformSubscriptionResponse(
				view.id(),
				view.organizationId(),
				view.planCode(),
				view.planName(),
				view.status(),
				view.startedAt(),
				view.version(),
				view.allowedTargets());
	}
}
