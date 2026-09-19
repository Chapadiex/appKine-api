package com.akine.clinical.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Un integrante del equipo tratante, tal como lo declara el cliente (RF-M10-005).
 *
 * <p>Viaja la <b>membership</b> y no la cuenta: es lo que identifica al profesional en este
 * centro, y la misma persona puede ser profesional en uno y administrativa en otro. Misma decision
 * que V23 para la disponibilidad y V28 para las habilitaciones.
 */
@Schema(name = "IntegranteDelEquipo",
		description = "Profesional del equipo tratante de un caso, con su rol")
public record IntegranteDelEquipoRequest(

		@Schema(description = "Membership del profesional en este centro", example = "31",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El integrante del equipo exige una membership")
		Long profesionalMembershipId,

		@Schema(description = "RESPONSABLE o TRATANTE. Ausente equivale a TRATANTE. NO otorga ni "
				+ "quita permisos: quien puede escribir en la historia lo deciden hc:write y la "
				+ "membership vigente. Esto expresa quien responde por el caso.",
				example = "TRATANTE", allowableValues = {"RESPONSABLE", "TRATANTE"})
		String rol) {
}
