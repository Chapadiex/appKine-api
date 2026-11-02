package com.akine.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * Apertura de la jornada de caja (RF-M20-001).
 *
 * <p><b>La fecha de negocio no se pide.</b> La deriva el servidor con la zona IANA de la sede:
 * abrir una jornada "para ayer" es un ajuste contable disfrazado de operacion, y dejar que el
 * cliente la elija habilita exactamente eso.
 */
@Schema(
		name = "AbrirCaja",
		description = "Abre el turno de caja de la sede con el saldo que se conto al empezar.")
public record AbrirCajaRequest(

		@Schema(
				description = "Lo que habia en el cajon al abrir. **Decimal exacto, nunca float.** "
						+ "Cero es un valor legitimo: una caja puede arrancar vacia.",
				example = "15000.00")
		@NotNull(message = "El saldo inicial es obligatorio")
		@DecimalMin(value = "0.00", message = "El saldo inicial no puede ser negativo") BigDecimal saldoInicial,

		@Schema(
				description = "Moneda de la jornada, ISO 4217. Se fija al abrir: un arqueo que suma "
						+ "pesos con dolares no se puede contar.",
				example = "ARS")
		@NotNull(message = "La moneda es obligatoria")
		@Pattern(regexp = "^[A-Z]{3}$", message = "La moneda debe ser un codigo de 3 letras") String moneda) {
}
