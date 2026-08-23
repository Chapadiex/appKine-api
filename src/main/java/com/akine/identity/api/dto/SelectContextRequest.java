package com.akine.identity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

/**
 * Contexto de trabajo elegido: Organizacion mas Consultorio (DP-02, ADR-0009).
 *
 * <p>Los dos son obligatorios porque no existe "estar en una organizacion" sin sede: toda
 * operacion clinica ocurre en un consultorio concreto y sin el no hay alcance contra el cual
 * filtrar una consulta.
 *
 * <p><b>Que se envie un par no significa que se pueda usar.</b> Quien decide es
 * {@code MembershipDirectory} leyendo la base en el momento (RN-M01-003); un par no accesible
 * se responde 404, igual que uno inexistente.
 */
@Schema(description = "Par Organizacion + Consultorio sobre el que se quiere trabajar")
public record SelectContextRequest(

		@Schema(description = "Identificador de la organizacion", example = "1",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@Positive(message = "El identificador de organizacion es obligatorio")
		long organizationId,

		@Schema(description = "Identificador del consultorio", example = "1",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@Positive(message = "El identificador de consultorio es obligatorio")
		long consultorioId) {
}
