package com.akine.clinical.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

/**
 * Atar un item del plan a una Autorizacion real de M17 (RF-M11-007).
 *
 * <p><b>No acepta {@code cantidadAutorizada}</b>, y esa ausencia es la decision: la cantidad sale
 * de la autorizacion, no del pedido. Aceptarla permitiria declarar diez donde el financiador
 * otorgo seis, que es exactamente el dato sin fuente que este vinculo existe para reemplazar.
 */
@Schema(description = "Vinculo entre un item del plan y una autorizacion de financiador")
public record VincularAutorizacionRequest(

		@Schema(description = "Item de la version VIGENTE del plan que se quiere atar",
				example = "310", requiredMode = Schema.RequiredMode.REQUIRED)
		@Positive(message = "El vinculo necesita el item del plan")
		long planItemId,

		@Schema(description = "Autorizacion de M17. Tiene que ser del MISMO paciente y habilitar hoy",
				example = "77", requiredMode = Schema.RequiredMode.REQUIRED)
		@Positive(message = "El vinculo necesita la autorizacion")
		long autorizacionId,

		@Schema(
				description = "Version del PLAN que el cliente leyo. El vinculo no crea version "
						+ "nueva, pero toca la vigente: dos operadores que abrieron la misma "
						+ "pantalla no pueden pisarse",
				example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
		long expectedVersion) {
}
