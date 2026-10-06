package com.akine.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motivo de la anulacion de un cobro (RF-M19-007).
 *
 * <p>Obligatorio por lo mismo que en una deuda o un egreso: plata que se devuelve sin explicacion es
 * exactamente lo que una auditoria busca. Y anular <b>no borra</b>: el cobro queda con su motivo, su
 * actor y su fecha, y conserva su comprobante.
 */
@Schema(
		name = "AnularCobro",
		description = "Anula un cobro con motivo obligatorio. **Anular no significa borrar**: el "
				+ "cobro y su comprobante quedan, sus imputaciones devuelven saldo a las deudas y sus "
				+ "movimientos de caja se revierten.")
public record AnularCobroRequest(

		@Schema(
				description = "Por que se anula. Queda en el cobro, en las reversiones de caja y en "
						+ "la auditoria.",
				example = "Cobrado al paciente equivocado")
		@NotBlank @Size(max = 280) String motivo) {
}
