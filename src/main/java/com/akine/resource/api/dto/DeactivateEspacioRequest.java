package com.akine.resource.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Baja logica de un espacio (RF-M04-006).
 *
 * <p>El motivo es obligatorio y no es burocracia: una baja sin motivo no se puede revisar seis
 * meses despues, que es exactamente cuando se revisa. Misma regla que la baja de una sede.
 */
@Schema(description = "Motivo de la baja logica del espacio")
public record DeactivateEspacioRequest(

		@Schema(
				description = "Por que se da de baja. Queda en la auditoria y en la fila del "
						+ "espacio",
				example = "Refaccion definitiva del ala oeste",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String reason) {
}
