package com.akine.billing.api.dto;

import com.akine.billing.application.PagoEgresoCommand;
import com.akine.billing.domain.MedioDePago;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Pago total o parcial de un egreso confirmado (RF-M22-002).
 *
 * <p><b>Un solo medio por pago</b>: pagarle a un profesional mitad en efectivo y mitad por
 * transferencia son dos hechos distintos, con dos comprobantes y probablemente dos dias.
 */
@Schema(
		name = "RegistrarPagoDeEgreso",
		description = "El acto de saldar, total o parcialmente. **Esto es lo que mueve la caja**, no "
				+ "la confirmacion del egreso.")
public record RegistrarPagoEgresoRequest(

		@Schema(
				description = "Puede ser menor que el saldo: el pago parcial esta previsto. Nunca "
						+ "mayor — 409 `egreso-saldo-insuficiente`.",
				example = "100000.00")
		@NotNull @DecimalMin(value = "0.01") BigDecimal importe,

		@Schema(
				description = "**Solo `EFECTIVO` afecta el arqueo y exige caja abierta.** Los demas "
						+ "medios asientan su movimiento igual —para que la operatoria del dia este "
						+ "completa— y no tocan el cajon: esa plata nunca estuvo ahi.",
				allowableValues = {"EFECTIVO", "TRANSFERENCIA", "TARJETA_DEBITO", "TARJETA_CREDITO", "OTRO"},
				example = "TRANSFERENCIA")
		@NotNull MedioDePago medio,

		@Schema(
				description = "Numero de transferencia o de recibo. Es lo que hace conciliable un "
						+ "pago que no es en efectivo.",
				example = "TRF-99213847")
		@Size(max = 80) String referencia,

		@Schema(
				description = "**Manda esta clave.** Sin ella, un doble click saca la plata del "
						+ "cajon dos veces.",
				example = "3b7e9a01-6c44-4a8d-9f21-0e5c8b2d7143")
		@Size(max = 80) String idempotencyKey) {

	public PagoEgresoCommand aDominio() {
		return new PagoEgresoCommand(importe, medio, referencia, idempotencyKey);
	}
}
