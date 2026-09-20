package com.akine.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motivo de una anulacion, de egreso o de pago (RF-M22-005).
 *
 * <p>El motivo es <b>obligatorio</b> y no es burocracia: plata que desaparece sin explicacion es
 * exactamente lo que una auditoria busca. Y anular <b>no borra</b> (RN-M22-002): la fila queda con
 * su motivo, su actor y su fecha.
 */
@Schema(
		name = "AnularEgreso",
		description = "Anula con motivo obligatorio. **Anular no significa borrar.**")
public record AnularEgresoRequest(

		@Schema(
				description = "Por que se anula. Queda en la fila y en la auditoria.",
				example = "Cargado con el importe equivocado")
		@NotBlank @Size(max = 280) String motivo) {
}
