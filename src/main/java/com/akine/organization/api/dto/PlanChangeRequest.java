package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Pedido de cambio del plan contratado.
 *
 * <p>Un downgrade se acepta aunque el uso actual ya supere los limites del plan nuevo, y no
 * toca un solo dato: lo que existe sigue operativo y consultable (RN-M01-004). Lo unico que
 * cambia es el futuro. Los limites ya excedidos vuelven como {@code warnings} en la respuesta,
 * no como un rechazo.
 *
 * <p>{@code expectedVersion} es la version de la suscripcion, no la de la organizacion: son
 * dos agregados distintos y cada uno lleva la suya.
 */
@Schema(description = "Plan a contratar y version esperada de la suscripcion")
public record PlanChangeRequest(

		@Schema(
				description = "Codigo del plan a contratar, del catalogo de GET /api/v1/plans",
				example = "PROFESIONAL",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El codigo de plan es obligatorio")
		@Size(max = 32, message = "El codigo de plan no puede superar los 32 caracteres")
		String planCode,

		@Schema(
				description = "Version de la suscripcion que el cliente cree vigente, tal como la "
						+ "devolvio el GET de la suscripcion",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version esperada es obligatoria para el bloqueo optimista")
		@PositiveOrZero(message = "La version no puede ser negativa")
		Long expectedVersion) {
}
