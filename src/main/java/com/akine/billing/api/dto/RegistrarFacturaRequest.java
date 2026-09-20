package com.akine.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Registra el comprobante externo del centro (RF-M21-005).
 *
 * <p><b>El sistema no lo genera ni lo numera: lo registra.</b> La factura se emite fuera de AKINE.
 * Lo unico que el sistema puede hacer —y hace— es impedir que el mismo numero quede asociado a dos
 * lotes del mismo financiador.
 */
@Schema(
		name = "RegistrarFacturaDePresentacion",
		description = "Asocia el comprobante que el centro emitio por este lote. Facturar **no es "
				+ "cobrar**: el lote sigue con el mismo saldo.")
public record RegistrarFacturaRequest(

		@Schema(description = "Numero del comprobante, tal como se emitio", example = "0001-00004521")
		@NotBlank @Size(max = 40) String numero,

		@Schema(description = "Fecha del comprobante", example = "2026-09-05")
		@NotNull LocalDate fecha) {
}
