package com.akine.billing.api.dto;

import com.akine.billing.application.ImputacionPosteriorCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Aplicar parte del saldo a favor de un cobro a una deuda (F-3, RF-M19-003).
 *
 * <p>Una deuda por pedido: asi la clave de idempotencia es de la imputacion y un reintento no imputa
 * dos veces.
 */
@Schema(
		name = "ImputarSaldoAFavor",
		description = "Aplica parte del saldo a favor (anticipo) de un cobro a una deuda. No mueve "
				+ "caja: la plata entro cuando se cobro.")
public record ImputarSaldoAFavorRequest(

		@Schema(description = "La deuda. De la misma persona y la misma sede que el cobro.", example = "9001")
		@NotNull(message = "La obligacion es obligatoria")
		@Positive(message = "La obligacion no es valida") Long obligacionId,

		@Schema(description = "Cuanto del saldo a favor se le aplica", example = "8500.00")
		@NotNull(message = "El importe es obligatorio")
		@DecimalMin(value = "0.01", message = "El importe tiene que ser mayor que cero") BigDecimal importe,

		@Schema(
				description = "Clave del cliente para que un reintento no impute dos veces. Reusarla "
						+ "con **otro** contenido devuelve 409.",
				example = "a1b2c3d4-0000-4000-8000-000000000001")
		@Size(max = 80, message = "La clave de idempotencia no puede superar los 80 caracteres") String idempotencyKey) {

	public ImputacionPosteriorCommand aDominio() {
		return new ImputacionPosteriorCommand(obligacionId, importe, idempotencyKey);
	}
}
