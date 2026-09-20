package com.akine.billing.api.dto;

import com.akine.billing.application.FinanciadorPagoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Un pago recibido de un financiador (RF-M21-007). */
@Schema(
		name = "PagoDeFinanciador",
		description = "Plata que el financiador pago contra un lote. **No es un Cobro**: no hay "
				+ "paciente, ni imputaciones, ni comprobante fiscal.")
public record FinanciadorPagoResponse(

		@Schema(example = "3301")
		long id,

		@Schema(example = "1204")
		long presentacionId,

		@Schema(example = "31")
		long financiadorId,

		@Schema(description = "Decimal exacto, nunca float", example = "85000.00")
		BigDecimal importe,

		@Schema(example = "ARS")
		String moneda,

		@Schema(
				allowableValues = {"EFECTIVO", "TRANSFERENCIA", "TARJETA_DEBITO",
						"TARJETA_CREDITO", "OTRO"},
				example = "TRANSFERENCIA")
		String medio,

		@Schema(description = "Cuando pago, que puede no ser cuando se cargo", example = "2026-09-18")
		LocalDate fechaPago,

		@Schema(example = "TRF-4471029")
		String referencia,

		@Schema(
				description = "El movimiento de caja que este pago genero (RN-M21-002). Presente "
						+ "al registrarlo; ausente al listarlo.",
				example = "7788")
		Long movimientoCajaId,

		@Schema(example = "2026-09-18T14:02:00Z")
		Instant registradoEn) {

	public static FinanciadorPagoResponse de(FinanciadorPagoView vista) {
		return new FinanciadorPagoResponse(
				vista.id(), vista.presentacionId(), vista.financiadorId(), vista.importe(),
				vista.moneda(), vista.medio().name(), vista.fechaPago(), vista.referencia(),
				vista.movimientoCajaId(), vista.registradoEn());
	}
}
