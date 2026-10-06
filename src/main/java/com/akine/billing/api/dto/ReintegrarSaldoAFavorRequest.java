package com.akine.billing.api.dto;

import com.akine.billing.application.ReintegroCommand;
import com.akine.billing.domain.MedioDePago;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Devolver en dinero parte del saldo a favor de un cobro (F-3, DP-06/ADR-0013). */
@Schema(
		name = "ReintegrarSaldoAFavor",
		description = "Devuelve en dinero parte del saldo a favor de un cobro. Sale de la caja: en "
				+ "efectivo exige caja abierta y plata en el cajon.")
public record ReintegrarSaldoAFavorRequest(

		@Schema(description = "Cuanto se devuelve. No puede superar el saldo a favor.", example = "1500.00")
		@NotNull @DecimalMin(value = "0.01") BigDecimal importe,

		@Schema(
				description = "Por donde sale la plata. Puede no ser el medio por el que entro.",
				allowableValues = {"EFECTIVO", "TRANSFERENCIA", "TARJETA_DEBITO", "TARJETA_CREDITO", "OTRO"},
				example = "EFECTIVO")
		@NotNull MedioDePago medio,

		@Schema(description = "Numero de operacion o lo que corresponda", example = "trf-55120")
		@Size(max = 120) String referencia,

		@Schema(
				description = "Por que se devuelve. Queda en el reintegro y en la auditoria.",
				example = "El paciente suspendio el tratamiento")
		@NotBlank @Size(max = 280) String motivo,

		@Schema(
				description = "Clave del cliente para que un reintento no devuelva dos veces. "
						+ "Reusarla con **otro** contenido devuelve 409.",
				example = "a1b2c3d4-0000-4000-8000-000000000002")
		@Size(max = 80) String idempotencyKey) {

	public ReintegroCommand aDominio() {
		return new ReintegroCommand(importe, medio, referencia, motivo, idempotencyKey);
	}
}
