package com.akine.organization.api.dto;

import com.akine.organization.domain.SubscriptionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Pedido de cambio de estado de la suscripcion.
 *
 * <p>{@code expectedStatus} es opcional pero fuertemente recomendado: si el estado actual no es
 * el que el actor creia, se responde 409 y se le pide revalidar. Sin el, alguien que mira una
 * pantalla desactualizada puede revertir en silencio una decision que otro tomo hace un minuto.
 *
 * <p>{@code reason} es obligatorio al suspender y al cancelar. No es burocracia: el historico
 * existe para responder "por que este centro dejo de funcionar" seis meses despues, y una fila
 * sin motivo no responde nada. Si falta donde corresponde, la respuesta es 400.
 */
@Schema(description = "Transicion de estado a aplicar sobre la suscripcion")
public record SubscriptionTransitionRequest(

		@Schema(
				description = "Estado destino. Debe estar entre los allowedTargets que publica "
						+ "GET /api/v1/organizations/{orgId}/subscription",
				example = "SUSPENDIDA",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El estado destino es obligatorio")
		SubscriptionStatus toStatus,

		@Schema(
				description = "Estado que el actor cree vigente. Si no coincide con el real, la "
						+ "operacion se rechaza con 409 en vez de aplicarse sobre una premisa falsa",
				example = "ACTIVA",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		SubscriptionStatus expectedStatus,

		@Schema(
				description = "Motivo declarado del cambio. Obligatorio al suspender y al cancelar",
				example = "Falta de pago de tres periodos consecutivos",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 500, message = "El motivo no puede superar los 500 caracteres")
		String reason) {
}
