package com.akine.resource.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Baja logica de una definicion de medida (RN-M06-001).
 *
 * <p>El motivo es obligatorio: sin el, la auditoria no responde por que un test dejo de usarse
 * seis meses despues, que es justamente cuando alguien lo pregunta.
 */
@Schema(description = "Motivo de la baja logica de una definicion de medida")
public record DeactivateMedicionDefinicionRequest(

		@Schema(
				description = "Por que se discontinua la medida. Queda en la fila y en la "
						+ "auditoria",
				example = "Test discontinuado por el protocolo 2026",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String reason) {
}
