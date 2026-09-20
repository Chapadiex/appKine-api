package com.akine.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Agrega una prestacion al borrador (RF-M21-002).
 *
 * <p><b>El importe no se pide:</b> lo copia el servidor del saldo de la obligacion. Dejar que el
 * cliente lo mande permitiria reclamarle a la obra social un numero que no tiene nada que ver con
 * la deuda devengada.
 */
@Schema(
		name = "AgregarItemAPresentacion",
		description = "Suma una deuda de financiador al borrador. **Incluirla no le mueve el "
				+ "saldo**: la obligacion se salda al conciliar el lote.")
public record AgregarItemRequest(

		@Schema(description = "La deuda que se va a reclamar", example = "9001")
		@NotNull Long obligacionId) {
}
