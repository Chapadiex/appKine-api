package com.akine.organization.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Baja logica de una sede (RF-M03-004).
 *
 * <p>El motivo es <b>obligatorio</b> y no es burocracia: la baja de una sede es irreversible
 * dentro del producto —02.01 no expone reactivacion, ningun RF la pide— y sin motivo declarado
 * la auditoria no responde por que seis meses despues, que es cuando se la consulta.
 *
 * <p>No hay borrado fisico. La sede queda INACTIVA, sigue siendo legible por id y conserva
 * intacta toda su historia (regla maestra 10).
 */
@Schema(description = "Motivo declarado de la baja de una sede")
public record DeactivateConsultorioRequest(

		@Schema(
				description = "Por que se da de baja la sede. Queda en la auditoria y en la "
						+ "propia fila",
				example = "Cierre definitivo de la sucursal",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El motivo de la baja es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres")
		String reason) {
}
