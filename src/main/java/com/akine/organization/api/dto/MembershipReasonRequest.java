package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motivo auditable de una transicion de estado de un vinculo.
 *
 * <p>Lo usan suspension, reactivacion y revocacion. El motivo es obligatorio en las tres: son
 * las operaciones que le quitan o le devuelven el acceso a una persona, y "por que" es
 * exactamente la pregunta que se hace en la revision seis meses despues.
 */
@Schema(description = "Motivo auditable de la operacion sobre el vinculo")
public record MembershipReasonRequest(

		@Schema(
				description = "Por que se ejecuta la operacion. Queda en la auditoria",
				example = "Licencia sin goce de haberes por tres meses",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo es obligatorio")
		@Size(max = 500, message = "El motivo no puede superar los 500 caracteres")
		String reason) {
}
