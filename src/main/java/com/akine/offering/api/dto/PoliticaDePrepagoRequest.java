package com.akine.offering.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Politica de prepago de una oferta (AKINE E-6, DP-06 / ADR-0013).
 *
 * <p>Va en un recurso propio y no en el {@code PUT} de la oferta: es configuracion del mostrador
 * —que la recepcion avise si el paciente no abono antes de ser atendido— y no de la prestacion.
 */
@Schema(name = "PoliticaDePrepagoRequest", description = "Politica de prepago de una oferta")
public record PoliticaDePrepagoRequest(

		@Schema(
				description = "Si la recepcion tiene que alertar cuando el paciente no dejo un "
						+ "anticipo antes de ser atendido. Alerta, nunca bloquea (DP-06)",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "exigePrepago es obligatorio")
		Boolean exigePrepago,

		@Schema(description = "Version de la oferta que se leyo", example = "3",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "expectedVersion es obligatorio")
		@PositiveOrZero
		Long expectedVersion) {
}
