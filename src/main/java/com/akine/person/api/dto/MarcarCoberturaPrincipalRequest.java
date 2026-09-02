package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Marca o desmarca la cobertura principal del paciente (RF-M08-004).
 *
 * <p>Lleva un booleano y no es un POST vacio porque desmarcar tambien es una operacion legitima:
 * un paciente puede tener coberturas sin que ninguna sea la preferida, y con un endpoint que solo
 * marca no habria forma de volver a ese estado sin dar de baja la cobertura.
 */
@Schema(description = "Nueva marca principal de la cobertura")
public record MarcarCoberturaPrincipalRequest(

		@Schema(
				description = "true la marca como principal, false la desmarca. Marcarla cuando ya "
						+ "hay otra principal vigente en ese periodo responde 409: no se desmarca "
						+ "a la otra en silencio",
				example = "true",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "Hay que declarar si la cobertura pasa a ser principal o deja de serlo")
		Boolean principal) {
}
