package com.akine.billing.api.dto;

import com.akine.billing.application.CuentaCorrienteDeFinanciador;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * El estado de cuenta de un financiador con la organizacion.
 *
 * <p><b>Esto no es la caja.</b> La caja es un cajon de una sede con jornada y arqueo; esto es una
 * relacion comercial que vive en meses y que nadie cuenta. Se tocan en un solo punto: el pago.
 */
@Schema(
		name = "CuentaCorrienteDeFinanciador",
		description = "Lo reclamado, lo debitado, lo cobrado y el saldo con un financiador. "
				+ "**Cruza sedes**: la relacion es de la organizacion.")
public record CuentaCorrienteFinanciadorResponse(

		@Schema(example = "31")
		long financiadorId,

		@Schema(example = "OSDE")
		String financiadorNombre,

		@Schema(description = "Decimal exacto, nunca float", example = "1250000.00")
		BigDecimal totalPresentado,

		@Schema(example = "48000.00")
		BigDecimal totalDebitado,

		@Schema(example = "1120000.00")
		BigDecimal totalCobrado,

		@Schema(
				description = "Lo reclamado que todavia no quedo explicado",
				example = "82000.00")
		BigDecimal saldo,

		@Schema(description = "Cuantos lotes entraron en el calculo", example = "12")
		int lotes) {

	public static CuentaCorrienteFinanciadorResponse de(CuentaCorrienteDeFinanciador cuenta) {
		return new CuentaCorrienteFinanciadorResponse(
				cuenta.financiadorId(), cuenta.financiadorNombre(), cuenta.totalPresentado(),
				cuenta.totalDebitado(), cuenta.totalCobrado(), cuenta.saldo(), cuenta.lotes());
	}
}
